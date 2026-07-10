package dev.njr.zync.server.pairing

import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.SqlDeviceRegistry
import java.security.MessageDigest
import java.security.SecureRandom

/** Result of redeeming a pairing code. */
sealed interface PairingResult {
    data class Paired(val deviceId: String) : PairingResult
    data class Rejected(val reason: String) : PairingResult
}

/**
 * DB-backed one-time pairing codes with a short window. `zync pair` calls [open] to
 * mint a code; the `/pair` endpoint calls [redeem] to register a device's public key
 * against a valid code. Codes are single-use and expire, so a stray client that
 * reaches `/pair` without the code (shown only in the operator's QR) can't self-register.
 */
class PairingManager(
    private val db: ZyncDatabase,
    private val registry: SqlDeviceRegistry,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Mint a one-time code valid until `now + ttlMillis`. */
    fun open(now: Long, ttlMillis: Long = DEFAULT_TTL): String {
        db.pairingQueries.purgeExpired(now)
        val code = randomCode()
        db.pairingQueries.insertCode(code, now + ttlMillis)
        return code
    }

    /** Redeem [code] for [devicePublicKey]; on success the device is registered and returned. */
    fun redeem(code: String, devicePublicKey: ByteArray, now: Long): PairingResult {
        db.pairingQueries.purgeExpired(now)
        val row = db.pairingQueries.getCode(code).executeAsOneOrNull()
            ?: return PairingResult.Rejected("unknown or expired code")
        if (row.used == 1L) return PairingResult.Rejected("code already used")
        if (row.expires_at <= now) return PairingResult.Rejected("code expired")

        db.pairingQueries.useCode(code)
        val deviceId = fingerprint(devicePublicKey)
        registry.register(deviceId, devicePublicKey, pairedAt = now)
        return PairingResult.Paired(deviceId)
    }

    private fun randomCode(): String {
        val bytes = ByteArray(6).also(random::nextBytes)
        return bytes.joinToString("") { CODE_ALPHABET[(it.toInt() and 0xFF) % CODE_ALPHABET.length].toString() }
    }

    companion object {
        const val DEFAULT_TTL: Long = 2 * 60 * 1000L
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no ambiguous chars

        /** Stable device id = first 16 hex of sha256(publicKey). */
        fun fingerprint(publicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKey)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                .take(16)
    }
}
