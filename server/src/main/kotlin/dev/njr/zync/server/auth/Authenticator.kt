package dev.njr.zync.server.auth

import dev.njr.zync.server.sha256Hex
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.response.respondText

/** Result of authenticating a call. */
sealed interface AuthOutcome {
    /** [deviceId] is the signing device's pairing id when device-authenticated, else null (browser/dev). */
    data class Authorized(val principal: String, val deviceId: String? = null) : AuthOutcome
    data class Unauthorized(val reason: String) : AuthOutcome
}

/**
 * Authenticates an inbound call (device signature or browser session). Suspends
 * because device verification hashes the request body into the signed canonical
 * string (requires the `DoubleReceive` plugin so routes can receive the body again).
 */
fun interface Authenticator {
    suspend fun authenticate(call: ApplicationCall): AuthOutcome

    /** Test/dev authenticator that lets everything through. */
    object AllowAll : Authenticator {
        override suspend fun authenticate(call: ApplicationCall): AuthOutcome = AuthOutcome.Authorized("allow-all")
    }
}

/**
 * Production authenticator: prefer an Ed25519 device signature (native clients),
 * else a browser session bearer token. Header contract for devices:
 * `X-Device-Id`, `X-Timestamp` (epoch ms), `X-Nonce`, `X-Signature` (base64).
 */
class ZyncAuthenticator(
    private val deviceVerifier: SignedRequestVerifier,
    private val sessions: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) : Authenticator {
    override suspend fun authenticate(call: ApplicationCall): AuthOutcome {
        val headers = call.request.headers
        val deviceId = headers["X-Device-Id"]
        if (deviceId != null) {
            val timestamp = headers["X-Timestamp"]?.toLongOrNull() ?: return AuthOutcome.Unauthorized("missing/invalid X-Timestamp")
            val nonce = headers["X-Nonce"] ?: return AuthOutcome.Unauthorized("missing X-Nonce")
            val signature = headers["X-Signature"] ?: return AuthOutcome.Unauthorized("missing X-Signature")
            val bodyHash = sha256Hex(call.receive<ByteArray>())
            val result = deviceVerifier.verify(
                method = call.request.httpMethod.value,
                path = call.request.path(),
                query = call.request.queryString(),
                bodySha256Hex = bodyHash,
                deviceId = deviceId,
                timestamp = timestamp,
                nonce = nonce,
                signatureBase64 = signature,
                now = now(),
            )
            return when (result) {
                is AuthResult.Authorized -> AuthOutcome.Authorized(result.principal, deviceId = result.principal)
                is AuthResult.Rejected -> AuthOutcome.Unauthorized(result.reason)
            }
        }
        val token = bearerToken(headers["Authorization"])
        if (token != null && sessions.validate(token, now())) return AuthOutcome.Authorized("browser")
        return AuthOutcome.Unauthorized("no valid credentials")
    }
}

/** Enforce [authenticator]; returns the authorized outcome, or responds 401 and returns null. */
suspend fun ApplicationCall.authorizedOrNull(authenticator: Authenticator): AuthOutcome.Authorized? =
    when (val outcome = authenticator.authenticate(this)) {
        is AuthOutcome.Authorized -> outcome
        is AuthOutcome.Unauthorized -> {
            respondText(outcome.reason, status = HttpStatusCode.Unauthorized)
            null
        }
    }

/** Enforce [authenticator] for this call; returns true if authorized, else responds 401. */
suspend fun ApplicationCall.requireAuth(authenticator: Authenticator): Boolean =
    authorizedOrNull(authenticator) != null
