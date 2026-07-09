package dev.njr.zync.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText

/** Result of authenticating a call. */
sealed interface AuthOutcome {
    data class Authorized(val principal: String) : AuthOutcome
    data class Unauthorized(val reason: String) : AuthOutcome
}

/** Authenticates an inbound call (device signature or browser session). */
fun interface Authenticator {
    fun authenticate(call: ApplicationCall): AuthOutcome

    /** Test/dev authenticator that lets everything through. */
    object AllowAll : Authenticator {
        override fun authenticate(call: ApplicationCall): AuthOutcome = AuthOutcome.Authorized("allow-all")
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
    override fun authenticate(call: ApplicationCall): AuthOutcome {
        val headers = call.request.headers
        val deviceId = headers["X-Device-Id"]
        if (deviceId != null) {
            val timestamp = headers["X-Timestamp"]?.toLongOrNull() ?: return AuthOutcome.Unauthorized("missing/invalid X-Timestamp")
            val nonce = headers["X-Nonce"] ?: return AuthOutcome.Unauthorized("missing X-Nonce")
            val signature = headers["X-Signature"] ?: return AuthOutcome.Unauthorized("missing X-Signature")
            return when (val r = deviceVerifier.verify(call.request.httpMethod.value, call.request.path(), deviceId, timestamp, nonce, signature, now())) {
                is AuthResult.Authorized -> AuthOutcome.Authorized(r.principal)
                is AuthResult.Rejected -> AuthOutcome.Unauthorized(r.reason)
            }
        }
        val token = bearerToken(headers["Authorization"])
        if (token != null && sessions.validate(token, now())) return AuthOutcome.Authorized("browser")
        return AuthOutcome.Unauthorized("no valid credentials")
    }

    private fun bearerToken(header: String?): String? =
        header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
}

/** Enforce [authenticator] for this call; returns true if authorized, else responds 401. */
suspend fun ApplicationCall.requireAuth(authenticator: Authenticator): Boolean =
    when (val outcome = authenticator.authenticate(this)) {
        is AuthOutcome.Authorized -> true
        is AuthOutcome.Unauthorized -> {
            respondText(outcome.reason, status = HttpStatusCode.Unauthorized)
            false
        }
    }
