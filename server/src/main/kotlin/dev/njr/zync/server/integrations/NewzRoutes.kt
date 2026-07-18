package dev.njr.zync.server.integrations

import dev.njr.zync.core.integrations.NewzHandoffResponse
import dev.njr.zync.core.integrations.NewzRedeemRequest
import dev.njr.zync.core.integrations.NewzRedeemResponse
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.bearerToken
import dev.njr.zync.server.auth.constantTimeEquals
import dev.njr.zync.server.auth.authorizedOrNull
import dev.njr.zync.server.pairing.ServerIdentity
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Handoff tokens live 60 seconds; a device may mint at most 10 per minute. */
private const val TOKEN_TTL_MS = 60_000L
private const val MINT_WINDOW_MS = 60_000L
private const val MINT_MAX_PER_WINDOW = 10L

/**
 * The zync→newz WebView handoff (spec: ../newz/zync-integration-spec.md).
 *
 * [identity] is the DEDICATED integration signing key — never the server's pairing
 * identity and never a device key. [redeemToken] is the separately-rotated service
 * credential newz presents on its private server-to-server redeem call; unset closes
 * redemption entirely. Tokens are compact JWTs (alg EdDSA) so newz can verify with
 * the published public key + `kid` alone.
 */
@OptIn(ExperimentalEncodingApi::class)
class NewzIntegration(
    val db: ZyncDatabase,
    private val identity: ServerIdentity,
    val publicAddress: String,
    val redeemToken: String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Key id: first 8 bytes of sha256(publicKey), hex — stable across restarts. */
    val kid: String = MessageDigest.getInstance("SHA-256").digest(identity.publicKey)
        .take(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    val publicKeyBase64: String get() = identity.publicKeyBase64

    fun freshJti(): String = ByteArray(16).also(random::nextBytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Compact JWT: `b64url(header).b64url(payload).b64url(ed25519 sig over the first two)`. */
    fun mintToken(deviceId: String, jti: String, issuedAtMs: Long, expiresAtMs: Long): String {
        val header = """{"alg":"EdDSA","typ":"JWT","kid":"$kid"}"""
        val payload = """{"iss":"zync","aud":"newz","sub":"$deviceId","iat":${issuedAtMs / 1000},""" +
            """"exp":${expiresAtMs / 1000},"jti":"$jti","return_path":"/newz/"}"""
        val signingInput = b64.encode(header.encodeToByteArray()) + "." + b64.encode(payload.encodeToByteArray())
        return signingInput + "." + b64.encode(identity.sign(signingInput.encodeToByteArray()))
    }

    fun nowMs(): Long = now()
}

/** Outcomes of a redemption attempt — all failures collapse to a generic 401 upstream. */
sealed interface RedeemOutcome {
    data class Redeemed(val deviceId: String) : RedeemOutcome
    data object Rejected : RedeemOutcome
}

/** Atomic one-time redemption: check + mark inside one transaction (sqlite single-writer). */
fun redeemJti(integration: NewzIntegration, jti: String): RedeemOutcome {
    val db = integration.db
    val now = integration.nowMs()
    return db.transactionWithResult {
        val row = db.newzHandoffQueries.getHandoff(jti).executeAsOneOrNull()
            ?: return@transactionWithResult RedeemOutcome.Rejected
        if (row.redeemed_at != null || row.expires_at <= now) return@transactionWithResult RedeemOutcome.Rejected
        val device = db.deviceQueries.getDevice(row.device_id).executeAsOneOrNull()
        if (device == null || device.revoked != 0L) return@transactionWithResult RedeemOutcome.Rejected
        db.newzHandoffQueries.markRedeemed(now, jti, now)
        db.newzHandoffQueries.prune(now - 24 * 60 * 60_000)
        RedeemOutcome.Redeemed(row.device_id)
    }
}

fun Route.newzRoutes(integration: NewzIntegration, auth: ServerAuth) {
    // Mint: device-signed only (a browser session carries no device identity).
    post("/integrations/newz/handoff") {
        val who = call.authorizedOrNull(auth.authenticator) ?: return@post
        val deviceId = who.deviceId ?: run {
            call.respondText("device auth required", status = HttpStatusCode.Forbidden)
            return@post
        }
        val now = integration.nowMs()
        val recent = integration.db.newzHandoffQueries.recentMints(deviceId, now - MINT_WINDOW_MS).executeAsOne()
        if (recent >= MINT_MAX_PER_WINDOW) {
            call.respondText("slow down", status = HttpStatusCode.TooManyRequests)
            return@post
        }
        val jti = integration.freshJti()
        val expires = now + TOKEN_TTL_MS
        integration.db.newzHandoffQueries.insertHandoff(jti, deviceId, now, expires)
        val token = integration.mintToken(deviceId, jti, now, expires)
        call.respond(
            NewzHandoffResponse(
                handoffUrl = "${integration.publicAddress}/newz/handoff?token=$token",
                expiresAt = Instant.ofEpochMilli(expires).toString(),
            ),
        )
    }

    // Redeem: newz's private server-to-server call, service-credential gated.
    post("/integrations/newz/redeem") {
        val token = integration.redeemToken
        val presented = bearerToken(call.request.headers[HttpHeaders.Authorization])
        if (token == null || presented == null || !constantTimeEquals(presented, token)) {
            call.respondText("not authorized", status = HttpStatusCode.Unauthorized)
            return@post
        }
        val jti = call.receive<NewzRedeemRequest>().jti
        when (val outcome = redeemJti(integration, jti)) {
            is RedeemOutcome.Redeemed -> call.respond(NewzRedeemResponse(outcome.deviceId))
            // Generic on every failure path: expired, replayed, unknown, revoked device.
            is RedeemOutcome.Rejected -> call.respondText("invalid handoff", status = HttpStatusCode.Unauthorized)
        }
    }
}
