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
    private val maxSessions: Int = 1024,
) {
    // Bounded LRU (token -> expiry), same pattern as `IdempotencyCache` in ApiRoutes.kt: an
    // access-order LinkedHashMap that self-evicts the least-recently-used entry once over cap.
    // This is a backstop against unbounded growth, not a replacement for the TTL expiry below —
    // an evicted-but-unexpired session simply logs that browser out early, same as a restart.
    private val sessions = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxSessions
    }

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

    /** Current number of live (not-yet-expired-or-evicted) sessions. Exposed for testing. */
    @Synchronized
    fun size(): Int = sessions.size

    companion object {
        private val random = SecureRandom()

        @OptIn(ExperimentalEncodingApi::class)
        fun randomToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64.UrlSafe.encode(it) }
    }
}
