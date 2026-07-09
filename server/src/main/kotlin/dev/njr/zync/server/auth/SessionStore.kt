package dev.njr.zync.server.auth

import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Browser session store for the single user. `login` checks a credential
 * (password today; a passkey/WebAuthn verifier can replace [credentialCheck]
 * later without touching the session lifecycle) and issues an opaque bearer token
 * with a TTL; `validate` gates protected routes; `logout` revokes.
 */
class SessionStore(
    private val ttlMillis: Long = 30 * 24 * 60 * 60 * 1000L,
    private val credentialCheck: (password: String) -> Boolean,
    private val tokenGenerator: () -> String = ::randomToken,
) {
    private val sessions = mutableMapOf<String, Long>() // token -> expiry

    @Synchronized
    fun login(password: String, now: Long): String? {
        if (!credentialCheck(password)) return null
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
