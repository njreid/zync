package dev.njr.zync.server

import dev.njr.zync.server.auth.DeviceRegistry
import dev.njr.zync.server.auth.NonceCache
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.auth.SignedRequestVerifier
import dev.njr.zync.server.auth.ZyncAuthenticator
import java.security.MessageDigest

/** Builds server components from environment configuration (12-factor). */
object ServerConfig {
    /**
     * Device auth is always on (keys come from pairing). Browser login is enabled only
     * when `ZYNC_ADMIN_PASSWORD` is set — a simple dev/fallback credential;
     * passkey/WebAuthn replaces it for the thin clients in M7 by swapping the
     * [SessionStore] credential check.
     */
    fun buildAuth(registry: DeviceRegistry, env: (String) -> String? = System::getenv): ServerAuth {
        val password = env("ZYNC_ADMIN_PASSWORD")
        val verifier = SignedRequestVerifier(registry, NonceCache(ttlMillis = 5 * 60 * 1000L))
        val sessions = SessionStore(credentialCheck = { candidate ->
            password != null && constantTimeEquals(candidate, password)
        })
        return ServerAuth(ZyncAuthenticator(verifier, sessions), sessions)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}
