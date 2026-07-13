package dev.njr.zync.server.auth

import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Browser session store for the single user. [mint] issues an opaque bearer token with a
 * TTL once the caller has verified the user's credential (a WebAuthn assertion — see
 * `webauthn.WebAuthnService`); `validate` gates protected routes; `logout` revokes. The
 * session lifecycle is deliberately independent of how the credential was checked.
 */
class SessionStore(
    private val ttlMillis: Long = 30 * 24 * 60 * 60 * 1000L,
    private val tokenGenerator: () -> String = ::randomToken,
) {
    private val sessions = mutableMapOf<String, Long>() // token -> expiry

    /** Issue a fresh session token. Call only after the credential has been verified. */
    @Synchronized
    fun mint(now: Long): String {
        val token = tokenGenerator()
        sessions[token] = now + ttlMillis
        return token
    }

    @Synchronized
    fun validate(token: String, now: Long): Boolean {
        val expiry = sessions[token] ?: return false
        if (expiry <= now) {
            sessions.remove(token)
            return false
        }
        return true
    }

    @Synchronized
    fun logout(token: String) {
        sessions.remove(token)
    }

    companion object {
        private val random = SecureRandom()

        @OptIn(ExperimentalEncodingApi::class)
        fun randomToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64.UrlSafe.encode(it) }
    }
}
