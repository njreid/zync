package dev.njr.zync.server

import dev.njr.zync.pairing.ChallengeDto
import dev.njr.zync.pairing.PairPendingDto
import dev.njr.zync.pairing.PairRequestBody
import dev.njr.zync.pairing.PairResultDto
import dev.njr.zync.pairing.PairingService
import dev.njr.zync.pairing.SessionDto
import dev.njr.zync.pairing.SessionRequestBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

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
    }
}
