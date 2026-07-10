package dev.njr.zync.server

import dev.njr.zync.server.auth.InMemoryDeviceRegistry
import dev.njr.zync.server.auth.NonceCache
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.auth.SignedRequestVerifier
import dev.njr.zync.server.auth.ZyncAuthenticator
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Builds server components from environment configuration (12-factor). */
object ServerConfig {
    private val log = LoggerFactory.getLogger("zync.config")

    /**
     * Build auth from env:
     * - `ZYNC_DEVICES_FILE`: lines `deviceId=base64Ed25519PublicKey` (native clients).
     * - `ZYNC_ADMIN_PASSWORD`: enables browser login.
     * If neither is set, runs open (AllowAll) with a loud warning — dev only.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun buildAuth(env: (String) -> String? = System::getenv): ServerAuth {
        val password = env("ZYNC_ADMIN_PASSWORD")
        val devicesFile = env("ZYNC_DEVICES_FILE")
        if (password == null && devicesFile == null) {
            log.warn("No auth configured (ZYNC_ADMIN_PASSWORD / ZYNC_DEVICES_FILE unset) — running OPEN. Do not expose publicly.")
            return ServerAuth.AllowAll
        }

        val registry = InMemoryDeviceRegistry()
        devicesFile?.let { path ->
            File(path).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val (id, b64) = line.split("=", limit = 2)
                    registry.register(id.trim(), Base64.decode(b64.trim()))
                }
        }
        val verifier = SignedRequestVerifier(registry, NonceCache(ttlMillis = 5 * 60 * 1000L))
        val sessions = SessionStore(credentialCheck = { candidate ->
            password != null && constantTimeEquals(candidate, password)
        })
        return ServerAuth(ZyncAuthenticator(verifier, sessions), sessions)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}
