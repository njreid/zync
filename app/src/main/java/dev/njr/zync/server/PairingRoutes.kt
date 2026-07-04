package dev.njr.zync.server

import dev.njr.zync.data.AllowedDeviceEntity
import dev.njr.zync.pairing.ChallengeDto
import dev.njr.zync.pairing.PairPendingDto
import dev.njr.zync.pairing.PairRequestBody
import dev.njr.zync.pairing.PairResultDto
import dev.njr.zync.pairing.PairingService
import dev.njr.zync.pairing.RemoteState
import dev.njr.zync.pairing.SessionDto
import dev.njr.zync.pairing.SessionRequestBody
import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private const val TAG = "zync"

/** Body of `POST /pair/approve` — the raw JSON the phone camera scanned off the desktop's QR. */
@Serializable
data class PairApproveBody(val payload: String)

/** Returned by `POST /pair/approve` — compare against the code the desktop is showing. */
@Serializable
data class ConfirmCodeDto(val confirmCode: String)

@Serializable
data class RemoteInfoDto(val ip: String, val tlsPort: Int, val certFingerprint: String)

@Serializable
data class RemoteStateDto(
    val enabled: Boolean,
    val ip: String? = null,
    val tlsPort: Int? = null,
    val certFingerprint: String? = null,
)

@Serializable
data class AllowedDeviceDto(
    val id: Long,
    val name: String,
    val addedAt: Long,
    val lastSeen: Long?,
    val revoked: Boolean,
)

fun AllowedDeviceEntity.toDto() = AllowedDeviceDto(id, name, addedAt, lastSeen, revoked)

/**
 * These device-management endpoints (remote-access toggling, `/pair/approve`, `/devices`) are meant to be
 * reachable only from the phone's own in-app WebView (the loopback connector) — never from a
 * paired desktop over the LAN connector, even one presenting an otherwise-valid session token.
 * [tokenGuard] doesn't (and, for `/pair/approve`, structurally can't — see [AuthGuard.isPairingPath])
 * enforce that connector restriction on its own, so every handler below checks it explicitly
 * before doing anything else.
 */
private suspend fun RoutingContext.requireLoopbackConnector(): Boolean {
    val connector = AuthGuard.classify(call.request.local.scheme)
    if (connector == AuthGuard.Connector.LAN) {
        call.respond(HttpStatusCode.Forbidden)
        return false
    }
    return true
}

/**
 * Pre-authentication pairing + session-bootstrap routes, mounted (when a [PairingService] is
 * supplied to `zyncModule`) on both connectors and exempted from [tokenGuard] entirely — see
 * `PairingService`'s class doc for the threat model that makes that safe.
 */
fun Route.pairingRoutes(pairing: PairingService) {
    route("/pair") {
        // Desktop polls this after showing its QR / after the phone has scanned it. Stays 202
        // "pending" until the phone-side approveScanned() has admitted the device and this same
        // nonce is presented back; a genuinely wrong pubkey/nonce (a different pairing attempt
        // entirely) is a 400 via the IllegalArgumentException StatusPages handler.
        post("/request") {
            val body = call.receive<PairRequestBody>()
            try {
                val result = pairing.completePairingRequest(body.devicePubkey, body.nonce)
                call.respond(HttpStatusCode.OK, PairResultDto(result.certFingerprint, result.confirmCode))
            } catch (e: IllegalStateException) {
                // Not yet approved / already consumed / expired / no pairing in progress: from
                // the desktop's point of view these all just mean "keep polling" (a replay of an
                // already-consumed nonce reads the same as "never approved" to an outside caller,
                // which is the point — it doesn't leak which case occurred).
                call.respond(HttpStatusCode.Accepted, PairPendingDto())
            }
        }

        get("/challenge") {
            requireNotNull(call.request.queryParameters["devicePubkey"]) {
                "devicePubkey query parameter is required"
            }
            call.respond(ChallengeDto(pairing.newChallenge()))
        }

        // Challenge-response session issuance. On success, the token is returned in the body
        // AND set as a cookie so browser-based callers (the LAN web UI) don't have to manage an
        // Authorization header themselves; on the LAN (HTTPS) connector that cookie gets
        // Secure + SameSite=Strict (hardening item 4) — over the loopback HTTP connector those
        // attributes would just make the cookie unusable, so they're only added when applicable.
        post("/session") {
            val body = call.receive<SessionRequestBody>()
            val token = pairing.issueSession(body.devicePubkey, body.challenge, body.signature)
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("invalid session request"))
                return@post
            }
            val isLan = AuthGuard.classify(call.request.local.scheme) == AuthGuard.Connector.LAN
            call.response.cookies.append(
                io.ktor.http.Cookie(
                    name = SESSION_COOKIE,
                    value = token,
                    path = "/",
                    httpOnly = true,
                    secure = isLan,
                    extensions = if (isLan) linkedMapOf("SameSite" to "Strict") else linkedMapOf(),
                ),
            )
            call.respond(HttpStatusCode.OK, SessionDto(token))
        }

        // Phone scans the desktop's QR (via `window.ZyncNative.scanPairingQr()`) and posts the
        // raw scanned payload here. Loopback-only (see `requireLoopbackConnector`): this is the
        // phone admitting a new device, so it must originate from the phone's own WebView, not
        // from the network. Note this path still lives under "/pair" so `AuthGuard.isPairingPath`
        // exempts it from `tokenGuard`'s normal token/session check entirely (see that guard's
        // doc) — the loopback-connector check here is the *only* access control on this route.
        post("/approve") {
            if (!requireLoopbackConnector()) return@post
            val body = call.receive<PairApproveBody>()
            val approved = pairing.approveScanned(body.payload)
            call.respond(HttpStatusCode.OK, ConfirmCodeDto(approved.confirmCode))
        }
    }

    // ---- settings-facing device management: loopback (in-app WebView) only ---------------------

    route("/remote") {
        post("/enable") {
            if (!requireLoopbackConnector()) return@post
            val manager = pairing.remoteAccess
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("remote access unavailable"))
            try {
                // Certificate generation/loading, the server stop/start swap, and NSD
                // registration are all blocking, multi-second-scale work. Running them on the
                // suspend function's default dispatcher would tie up the Netty engine's own
                // event-loop thread for that entire window — which was observed on-device to make
                // the *response to this very request* undeliverable ("Failed to fetch": the
                // connection this request arrived on stalled for the whole blocking window instead
                // of promptly ACKing/framing the eventual response). Dispatchers.IO keeps the
                // event loop free to service this (and other) connections while the heavy lifting
                // runs elsewhere; the coroutine resumes back onto the call pipeline to respond.
                val info = withContext(Dispatchers.IO) { manager.enable() }
                call.respond(HttpStatusCode.OK, RemoteInfoDto(info.ip, info.tlsPort, info.certFingerprint))
            } catch (t: Throwable) {
                Log.e(TAG, "remote enable failed", t)
                call.respond(HttpStatusCode.InternalServerError, ErrorDto(t.message ?: "remote enable failed"))
            }
        }

        post("/disable") {
            if (!requireLoopbackConnector()) return@post
            try {
                withContext(Dispatchers.IO) { pairing.remoteAccess?.disable() }
                call.respond(HttpStatusCode.OK, RemoteStateDto(enabled = false))
            } catch (t: Throwable) {
                Log.e(TAG, "remote disable failed", t)
                call.respond(HttpStatusCode.InternalServerError, ErrorDto(t.message ?: "remote disable failed"))
            }
        }

        get("/state") {
            if (!requireLoopbackConnector()) return@get
            when (val state = pairing.remoteAccess?.state()) {
                is RemoteState.Enabled -> call.respond(
                    RemoteStateDto(true, state.info.ip, state.info.tlsPort, state.info.certFingerprint),
                )
                else -> call.respond(RemoteStateDto(enabled = false))
            }
        }
    }

    route("/devices") {
        get {
            if (!requireLoopbackConnector()) return@get
            call.respond(pairing.listDevices().map { it.toDto() })
        }

        post("/{id}/revoke") {
            if (!requireLoopbackConnector()) return@post
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid device id"))
            pairing.revoke(id)
            call.respond(HttpStatusCode.OK)
        }
    }
}
