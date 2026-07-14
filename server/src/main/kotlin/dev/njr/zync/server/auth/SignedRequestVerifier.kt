package dev.njr.zync.server.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

/** Outcome of an auth check. */
sealed interface AuthResult {
    data class Authorized(val principal: String) : AuthResult
    data class Rejected(val reason: String) : AuthResult
}

/**
 * Verifies an Ed25519 signed request from a native device. The signature covers a
 * canonical `method\npath\nquery\ntimestamp\nnonce\nsha256(body)` string, so the
 * authorization is self-contained for the specific payload — not just transport-
 * protected by TLS. Rejects unknown/revoked devices, stale timestamps (clock-skew
 * window), replayed nonces, and bad signatures.
 */
class SignedRequestVerifier(
    private val registry: DeviceRegistry,
    private val nonces: NonceCache,
    private val windowMillis: Long = 5 * 60 * 1000L,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun verify(
        method: String,
        path: String,
        query: String,
        bodySha256Hex: String,
        deviceId: String,
        timestamp: Long,
        nonce: String,
        signatureBase64: String,
        now: Long,
    ): AuthResult {
        val publicKey = registry.publicKey(deviceId) ?: return AuthResult.Rejected("unknown device")
        if (registry.isRevoked(deviceId)) return AuthResult.Rejected("revoked device")
        if (abs(now - timestamp) > windowMillis) return AuthResult.Rejected("stale timestamp")

        val signature = try {
            Base64.decode(signatureBase64)
        } catch (_: Exception) {
            return AuthResult.Rejected("malformed signature")
        }
        val message = canonicalString(method, path, query, timestamp, nonce, bodySha256Hex).encodeToByteArray()
        if (!Ed25519.verify(publicKey, message, signature)) return AuthResult.Rejected("bad signature")

        // Record the nonce only after the signature is valid (don't let junk fill the cache).
        if (!nonces.checkAndRecord(nonce, now)) return AuthResult.Rejected("replayed nonce")
        return AuthResult.Authorized(deviceId)
    }

    companion object {
        fun canonicalString(
            method: String,
            path: String,
            query: String,
            timestamp: Long,
            nonce: String,
            bodySha256Hex: String,
        ): String = "$method\n$path\n$query\n$timestamp\n$nonce\n$bodySha256Hex"
    }
}
