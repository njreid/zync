package dev.njr.zync.server

import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.DeviceRegistry
import dev.njr.zync.server.auth.NonceCache
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.auth.SignedRequestVerifier
import dev.njr.zync.server.auth.ZyncAuthenticator
import dev.njr.zync.server.auth.webauthn.ChallengeStore
import dev.njr.zync.server.auth.webauthn.WebAuthnConfig
import dev.njr.zync.server.auth.webauthn.WebAuthnEndpoint
import dev.njr.zync.server.auth.webauthn.WebAuthnService
import dev.njr.zync.server.auth.webauthn.WebauthnCredentialStore

/** Builds server components from environment configuration (12-factor). */
object ServerConfig {
    /**
     * Device auth is always on (keys come from pairing). Browser session auth is passwordless:
     * a session is minted only after a verified WebAuthn assertion (see [buildWebAuthn]).
     */
    fun buildAuth(registry: DeviceRegistry): ServerAuth {
        val verifier = SignedRequestVerifier(registry, NonceCache(ttlMillis = 5 * 60 * 1000L))
        val sessions = SessionStore()
        return ServerAuth(ZyncAuthenticator(verifier, sessions), sessions)
    }

    /**
     * Browser passkey auth, enabled when the relying-party identity is configured
     * (`ZYNC_WEBAUTHN_RP_ID` + `ZYNC_WEBAUTHN_ORIGIN`). Enrolment is gated by
     * `ZYNC_WEBAUTHN_REG_TOKEN`; when absent, registration is disabled but existing passkeys
     * still work. Returns null (browser UI ungated) when WebAuthn isn't configured.
     */
    fun buildWebAuthn(
        db: ZyncDatabase,
        sessions: SessionStore,
        env: (String) -> String? = System::getenv,
    ): WebAuthnEndpoint? {
        val rpId = env("ZYNC_WEBAUTHN_RP_ID") ?: return null
        val origin = env("ZYNC_WEBAUTHN_ORIGIN") ?: return null
        val config = WebAuthnConfig(
            rpId = rpId,
            rpName = env("ZYNC_WEBAUTHN_RP_NAME") ?: "zync",
            origin = origin,
            userHandle = "zync-admin".encodeToByteArray(),
            userName = env("ZYNC_WEBAUTHN_USER") ?: "zync",
            userDisplayName = env("ZYNC_WEBAUTHN_USER") ?: "zync",
        )
        val service = WebAuthnService(config, WebauthnCredentialStore(db), ChallengeStore())
        return WebAuthnEndpoint(service, sessions, env("ZYNC_WEBAUTHN_REG_TOKEN"))
    }
}
