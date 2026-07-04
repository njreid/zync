package dev.njr.zync.pairing

import dev.njr.zync.data.AllowedDeviceDao
import dev.njr.zync.data.AllowedDeviceEntity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Orchestrates the whole device-pairing + session lifecycle for the local pairing/sync server.
 *
 * Per spec §8b, the **desktop** originates the pairing nonce, not the phone:
 *
 *  1. Desktop generates its Ed25519 keypair and a random one-time nonce, and displays a QR
 *     encoding `{devicePubkey, deviceName, nonce}`.
 *  2. [approveScanned] — the phone's camera scans that QR (physical proximity to the desktop's
 *     screen is the trust anchor) and the user confirms in the UI. This call is entirely
 *     self-sufficient: it parses the payload, admits the device into [AllowedDeviceDao], and
 *     records an *approved* pairing for that `devicePubkey` — `{nonce, confirmCode, createdAt}`
 *     — keyed by the pubkey. It returns the `confirmCode` for the phone to display.
 *  3. [completePairingRequest] — the desktop polls this with `{devicePubkey, nonce}` over TLS.
 *     It succeeds only once there is an approved record for that pubkey whose nonce matches
 *     (constant-time), returning the server's cert fingerprint (for TLS pinning) and the same
 *     `confirmCode`. The user visually compares the confirm code shown on phone and desktop —
 *     this is the MITM defense for the otherwise-unauthenticated pairing handshake. The record
 *     is consumed (single-use) on success.
 *  4. [issueSession] / [validateSession] — ongoing challenge-response auth: the desktop asks for
 *     a [newChallenge], signs it with its Ed25519 private key, and trades that signature for an
 *     opaque bearer token.
 *
 * THREAT MODEL: `approveScanned`, `completePairingRequest`, and `newChallenge` are necessarily
 * reachable pre-authentication — there is no allow-listed device yet to authenticate against.
 * Their security rests on:
 *   - possession of the phone (only someone holding the unlocked phone, looking at the desktop's
 *     screen, can trigger `approveScanned`),
 *   - the one-time, short-TTL nonce binding a specific desktop to a specific approved pairing (an
 *     attacker who doesn't see the QR can't guess the nonce in time),
 *   - the confirm-code comparison, which lets the user visually catch a network attacker who
 *     tried to race their own `/pair/request` against the real desktop's,
 *   - TLS certificate pinning using the fingerprint returned by `completePairingRequest` (so a
 *     network attacker can't MITM the *session* traffic even though the *pairing* handshake is
 *     unauthenticated).
 * Approved-pairing records and challenges are single-use and short-lived specifically to bound
 * the window in which a network observer (who is not the pinned TLS channel) could attempt to
 * replay them.
 */
class PairingService(
    private val dao: AllowedDeviceDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val randomNonce: () -> String,
) {

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

    /** One approved-but-not-yet-completed pairing, keyed by `devicePubkey` in [approvedPairings]. */
    private data class ApprovedPairing(
        val nonce: String,
        val confirmCode: String,
        val expiresAt: Long,
        var consumed: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // devicePubkey -> the approval recorded by approveScanned, awaiting completePairingRequest.
    private val approvedPairings = ConcurrentHashMap<String, ApprovedPairing>()

    // Guards all read-then-mutate sequences on `approvedPairings` (insert-and-record in
    // approveScanned; the consumed-check-then-set in completePairingRequest). Without this mutex,
    // two concurrent completePairingRequest calls for the same pubkey (plausible under Ktor's
    // multithreaded dispatcher) could both observe `consumed == false` before either sets it,
    // letting a single-use nonce complete a pairing twice.
    private val approvalMutex = Mutex()

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

    /**
     * Settable back-reference to the [RemoteAccessManager] that owns the remote-access lifecycle,
     * so the remote-access device-management routes (mounted from [dev.njr.zync.server.pairingRoutes],
     * which only receives this [PairingService]) can reach it. `ZyncApp` wires this once
     * `remoteAccess` is constructed, exactly once at startup, on the app's main/init thread; it is
     * then read from server-dispatcher threads handling requests. `@Volatile` so that write is
     * guaranteed visible to those reader threads without each one needing its own synchronization.
     * Nullable/settable rather than a constructor parameter because `RemoteAccessManager` itself
     * depends on this `PairingService` — a constructor cycle.
     */
    @Volatile var remoteAccess: RemoteAccessManager? = null

    fun setCertFingerprint(fp: String) {
        certFingerprint = fp
    }

    /** All allowed devices (paired, revoked or not), most-recently-added first. */
    suspend fun listDevices(): List<AllowedDeviceEntity> = dao.observeAll().first()

    /**
     * The phone scans a QR the desktop is showing (`{devicePubkey, deviceName, nonce}`) and the
     * user confirms in the UI. Self-sufficient — no prior server-side state is required. Admits
     * the device into [AllowedDeviceDao] and records an approved pairing for `devicePubkey` so a
     * subsequent [completePairingRequest] from the desktop can complete. Returns the confirm code
     * for the phone to display so the user can compare it against the desktop's.
     */
    suspend fun approveScanned(scannedPayload: String): ApprovedDevice {
        val payload = try {
            json.decodeFromString(QrPayload.serializer(), scannedPayload)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("malformed QR payload", e)
        }

        val confirmCode = deriveConfirmCode(payload.nonce)

        return approvalMutex.withLock {
            sweepApprovedPairings()

            // Re-pairing a device we already know about (re-scan after e.g. a factory reset, or
            // the user deliberately re-adding a previously revoked device) must be a clean,
            // idempotent success rather than bubbling up the DAO's unique-constraint exception —
            // that would otherwise surface to the desktop as an unhandled 500. If the device is
            // currently revoked, re-approving it here is a deliberate re-add, so un-revoke it.
            val existing = dao.byPubkey(payload.devicePubkey)
            val id = if (existing != null) {
                if (existing.revoked) {
                    dao.setRevoked(existing.id, false)
                }
                existing.id
            } else {
                dao.insert(
                    AllowedDeviceEntity(
                        name = payload.deviceName,
                        pubkey = payload.devicePubkey,
                        addedAt = now(),
                        lastSeen = null,
                        revoked = false,
                    ),
                )
            }
            approvedPairings[payload.devicePubkey] = ApprovedPairing(
                nonce = payload.nonce,
                confirmCode = confirmCode,
                expiresAt = now() + PAIRING_TTL_MS,
            )

            ApprovedDevice(
                id = id,
                pubkey = payload.devicePubkey,
                name = payload.deviceName,
                confirmCode = confirmCode,
            )
        }
    }

    /** Must be called while holding [approvalMutex]. Drops consumed and time-expired approvals. */
    private fun sweepApprovedPairings() {
        val nowMs = now()
        approvedPairings.entries.removeIf { (_, pairing) -> pairing.consumed || nowMs > pairing.expiresAt }
    }

    /** Drops sessions past [SessionInfo.expiresAt]. Safe to call without a lock: [ConcurrentHashMap]. */
    private fun sweepSessions() {
        val nowMs = now()
        sessions.entries.removeIf { (_, info) -> nowMs >= info.expiresAt }
    }

    /** Drops challenges past their expiry. Safe to call without a lock: [ConcurrentHashMap]. */
    private fun sweepChallenges() {
        val nowMs = now()
        challenges.entries.removeIf { (_, expiresAt) -> nowMs > expiresAt }
    }

    // ---- test-only visibility into map sizes, to assert sweeping actually evicts entries ----
    internal fun approvedPairingsSizeForTest(): Int = approvedPairings.size
    internal fun sessionsSizeForTest(): Int = sessions.size
    internal fun challengesSizeForTest(): Int = challenges.size

    /**
     * The desktop polls this with the same `{devicePubkey, nonce}` it put in its QR. Succeeds
     * only once [approveScanned] has recorded a matching, unexpired, not-yet-consumed approval
     * for `devicePubkeyB64` — at which point the record is consumed (single-use: this exact
     * pubkey+nonce pairing can never complete a second `/pair/request`).
     */
    suspend fun completePairingRequest(devicePubkeyB64: String, nonceFromDesktop: String): PairingResult {
        // The lookup-then-consume sequence must be atomic w.r.t. itself across callers: without
        // this lock, two concurrent callers could both find the same not-yet-consumed record and
        // both proceed, defeating the single-use guarantee (see the note on `approvalMutex` above).
        return approvalMutex.withLock {
            sweepApprovedPairings()

            // Walk every recorded approval and compare the pubkey in constant time, rather than a
            // direct map lookup by the raw key, so a caller can't use response timing to learn how
            // many characters of a guessed pubkey matched a real, approved one.
            var matchedKey: String? = null
            var record: ApprovedPairing? = null
            for ((pubkey, pairing) in approvedPairings) {
                if (Crypto.constantTimeEquals(pubkey, devicePubkeyB64)) {
                    matchedKey = pubkey
                    record = pairing
                }
            }
            val current = record
                ?: throw IllegalStateException("no pairing in progress")
            if (current.consumed) {
                throw IllegalStateException("pairing nonce already used")
            }
            if (now() > current.expiresAt) {
                throw IllegalStateException("pairing nonce expired")
            }
            if (!Crypto.constantTimeEquals(current.nonce, nonceFromDesktop)) {
                throw IllegalArgumentException("nonce mismatch")
            }

            val device = dao.byPubkey(matchedKey!!)
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
        sweepSessions()
        sweepChallenges()

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
        sweepSessions()
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
        sweepChallenges()
        val challenge = randomNonce()
        challenges[challenge] = now() + CHALLENGE_TTL_MS
        return challenge
    }

    suspend fun revoke(id: Long) {
        dao.setRevoked(id, true)
    }

    private fun deriveConfirmCode(nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte -> "%02X".format(byte) }
        return hex.take(CONFIRM_CODE_LENGTH)
    }

    companion object {
        private const val PAIRING_TTL_MS = 5 * 60 * 1000L
        private const val CHALLENGE_TTL_MS = 2 * 60 * 1000L
        private const val SESSION_TTL_MS = 24 * 60 * 60 * 1000L
        private const val CONFIRM_CODE_LENGTH = 8
    }
}
