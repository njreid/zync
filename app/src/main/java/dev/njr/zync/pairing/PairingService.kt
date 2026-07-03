package dev.njr.zync.pairing

import dev.njr.zync.data.AllowedDeviceDao
import dev.njr.zync.data.AllowedDeviceEntity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Orchestrates the whole device-pairing + session lifecycle for the local pairing/sync server:
 *
 *  1. [beginPairing] — the phone mints a one-time nonce + human confirm code and displays it
 *     (e.g. as a QR / short code) so the desktop can address this specific pairing attempt.
 *  2. [approveScanned] — the phone scans a QR shown by the desktop (carrying the desktop's
 *     Ed25519 pubkey, a display name, and the nonce from step 1) and, on a match, admits the
 *     device into [AllowedDeviceDao].
 *  3. [completePairingRequest] — the desktop polls this until the phone has approved it; once
 *     approved it receives the server's cert fingerprint (for TLS pinning) and the confirm code.
 *  4. [issueSession] / [validateSession] — ongoing challenge-response auth: the desktop asks for
 *     a [newChallenge], signs it with its Ed25519 private key, and trades that signature for an
 *     opaque bearer token.
 *
 * THREAT MODEL: `beginPairing`, `approveScanned`, `completePairingRequest`, and `newChallenge`
 * are necessarily reachable pre-authentication — there is no allow-listed device yet to
 * authenticate against. Their security rests on:
 *   - possession of the phone (only someone holding the unlocked phone can trigger
 *     `beginPairing`/`approveScanned`),
 *   - the one-time, short-TTL QR nonce binding a specific desktop to a specific pairing attempt
 *     (an attacker who doesn't see the QR can't guess the nonce in time),
 *   - TLS certificate pinning using the fingerprint returned by `completePairingRequest` (so a
 *     network attacker can't MITM the *session* traffic even though the *pairing* handshake is
 *     unauthenticated).
 * Nonces and challenges are single-use and short-lived specifically to bound the window in which
 * a network observer (who is not the pinned TLS channel) could attempt to replay them.
 */
class PairingService(
    private val dao: AllowedDeviceDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val randomNonce: () -> String,
) {

    data class PendingPairing(
        val nonce: String,
        val confirmCode: String,
        val expiresAt: Long,
    )

    data class ApprovedDevice(
        val id: Long,
        val pubkey: String,
        val name: String,
        val confirmCode: String,
    )

    data class PairingResult(
        val certFingerprint: String,
        val confirmCode: String,
    )

    private data class PendingState(
        val nonce: String,
        val confirmCode: String,
        val expiresAt: Long,
        var approvedPubkey: String? = null,
        var consumed: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // Single in-flight pairing attempt: a new beginPairing() supersedes whatever was pending.
    @Volatile private var pending: PendingState? = null

    // Guards all read-then-mutate sequences on `pending`'s fields (`consumed`, `approvedPubkey`).
    // @Volatile above only publishes the *reference*; without this mutex, two concurrent
    // completePairingRequest calls (plausible under Ktor's multithreaded dispatcher) could both
    // observe `consumed == false` before either sets it, letting a single-use nonce complete a
    // pairing twice. Every check-then-set on `pending`'s fields must happen inside this lock.
    private val pendingMutex = Mutex()

    // token -> (devicePubkey, expiresAt). In-memory only: sessions do not survive a process
    // restart, which is fine for a local pairing/sync server (the desktop just re-authenticates).
    // The device pubkey is retained so validateSession can re-check revocation status live,
    // rather than trusting a snapshot taken at issuance time — this is what makes revoke()
    // effectively immediate instead of merely capping exposure at the session TTL.
    private data class SessionInfo(val devicePubkey: String, val expiresAt: Long)
    private val sessions = ConcurrentHashMap<String, SessionInfo>()

    // challenge -> expiresAt. Removed (consumed) the moment it's looked at in issueSession,
    // whether or not the accompanying signature turns out to be valid, so a challenge can never
    // be redeemed twice.
    private val challenges = ConcurrentHashMap<String, Long>()

    @Volatile private var certFingerprint: String = ""

    fun setCertFingerprint(fp: String) {
        certFingerprint = fp
    }

    fun beginPairing(): PendingPairing {
        val nonce = randomNonce()
        val confirmCode = deriveConfirmCode(nonce)
        val expiresAt = now() + PAIRING_TTL_MS
        pending = PendingState(nonce = nonce, confirmCode = confirmCode, expiresAt = expiresAt)
        return PendingPairing(nonce, confirmCode, expiresAt)
    }

    suspend fun approveScanned(scannedPayload: String): ApprovedDevice {
        val payload = try {
            json.decodeFromString(QrPayload.serializer(), scannedPayload)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("malformed QR payload", e)
        }

        return pendingMutex.withLock {
            val current = pending
                ?: throw IllegalArgumentException("no pairing in progress")
            if (now() > current.expiresAt) {
                throw IllegalArgumentException("pairing nonce expired")
            }
            if (!Crypto.constantTimeEquals(current.nonce, payload.nonce)) {
                throw IllegalArgumentException("nonce mismatch")
            }

            val id = dao.insert(
                AllowedDeviceEntity(
                    name = payload.deviceName,
                    pubkey = payload.devicePubkey,
                    addedAt = now(),
                    lastSeen = null,
                    revoked = false,
                ),
            )
            current.approvedPubkey = payload.devicePubkey

            ApprovedDevice(
                id = id,
                pubkey = payload.devicePubkey,
                name = payload.deviceName,
                confirmCode = current.confirmCode,
            )
        }
    }

    suspend fun completePairingRequest(devicePubkeyB64: String, nonceFromDesktop: String): PairingResult {
        // The consumed-check and consumed-set must be atomic w.r.t. each other: without this
        // lock, two concurrent callers could both read `consumed == false` and both proceed,
        // defeating the single-use guarantee (see the note on `pendingMutex` above).
        return pendingMutex.withLock {
            val current = pending
                ?: throw IllegalStateException("no pairing in progress")
            if (current.consumed) {
                throw IllegalStateException("pairing nonce already used")
            }
            val approvedPubkey = current.approvedPubkey
                ?: throw IllegalStateException("pairing not yet approved by phone")
            if (now() > current.expiresAt) {
                throw IllegalStateException("pairing nonce expired")
            }
            if (!Crypto.constantTimeEquals(approvedPubkey, devicePubkeyB64)) {
                throw IllegalArgumentException("pubkey mismatch")
            }
            if (!Crypto.constantTimeEquals(current.nonce, nonceFromDesktop)) {
                throw IllegalArgumentException("nonce mismatch")
            }

            val device = dao.byPubkey(devicePubkeyB64)
            if (device == null || device.revoked) {
                throw IllegalStateException("device is no longer allowed")
            }

            // One-time: this nonce can never complete a second pairing request. Setting this
            // flag is still inside the critical section started above, so the check above and
            // this set form one atomic compare-and-set.
            current.consumed = true

            PairingResult(certFingerprint = certFingerprint, confirmCode = current.confirmCode)
        }
    }

    suspend fun issueSession(devicePubkeyB64: String, challenge: String, signatureB64: String): String? {
        val device = dao.byPubkey(devicePubkeyB64) ?: return null
        if (device.revoked) return null

        // Consume the challenge unconditionally (single-use), regardless of whether the
        // signature below turns out to be valid.
        val challengeExpiresAt = challenges.remove(challenge) ?: return null
        if (now() > challengeExpiresAt) return null

        val challengeBytes = challenge.toByteArray(Charsets.UTF_8)
        if (!Crypto.verifyEd25519(devicePubkeyB64, challengeBytes, signatureB64)) return null

        val token = randomNonce()
        sessions[token] = SessionInfo(devicePubkeyB64, now() + SESSION_TTL_MS)
        dao.touch(device.id, now())
        return token
    }

    /**
     * True iff [token] is a live, unexpired session for a device that is not (as of *right now*)
     * revoked. Re-checking revocation here — rather than trusting a flag captured at
     * [issueSession] time — is what makes [revoke] take effect immediately instead of merely
     * bounding exposure by the session TTL.
     */
    suspend fun validateSession(token: String): Boolean {
        val nowMs = now()
        // Walk every live session rather than doing a direct map lookup by the raw token, and
        // compare with a constant-time comparator, so a caller can't use response timing to
        // learn how many characters of a guessed token were correct.
        var match: SessionInfo? = null
        for ((storedToken, info) in sessions) {
            if (Crypto.constantTimeEquals(storedToken, token)) {
                match = info
            }
        }
        val info = match ?: return false
        if (nowMs >= info.expiresAt) return false
        val device = dao.byPubkey(info.devicePubkey) ?: return false
        return !device.revoked
    }

    fun newChallenge(): String {
        val challenge = randomNonce()
        challenges[challenge] = now() + CHALLENGE_TTL_MS
        return challenge
    }

    suspend fun revoke(id: Long) {
        dao.setRevoked(id, true)
    }

    private fun deriveConfirmCode(nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0xFF) shl 16) or
            ((digest[1].toInt() and 0xFF) shl 8) or
            (digest[2].toInt() and 0xFF)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    companion object {
        private const val PAIRING_TTL_MS = 2 * 60 * 1000L
        private const val CHALLENGE_TTL_MS = 2 * 60 * 1000L
        private const val SESSION_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
