package dev.njr.zync.pairing

import dev.njr.zync.data.AllowedDeviceDao
import dev.njr.zync.data.AllowedDeviceEntity
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** In-memory fake of the Room DAO so these tests stay pure-JVM (no Robolectric needed). */
private class FakeAllowedDeviceDao : AllowedDeviceDao {
    private val nextId = AtomicLong(1)
    private val rows = ConcurrentHashMap<Long, AllowedDeviceEntity>()
    private val flow = MutableStateFlow<List<AllowedDeviceEntity>>(emptyList())

    override suspend fun insert(device: AllowedDeviceEntity): Long {
        if (rows.values.any { it.pubkey == device.pubkey }) {
            throw IllegalStateException("unique constraint violation on pubkey")
        }
        val id = nextId.getAndIncrement()
        rows[id] = device.copy(id = id)
        flow.value = rows.values.sortedByDescending { it.addedAt }
        return id
    }

    override suspend fun byPubkey(pubkey: String): AllowedDeviceEntity? =
        rows.values.firstOrNull { it.pubkey == pubkey }

    override fun observeAll(): Flow<List<AllowedDeviceEntity>> = flow

    override suspend fun setRevoked(id: Long, revoked: Boolean) {
        rows[id]?.let { rows[id] = it.copy(revoked = revoked) }
        flow.value = rows.values.sortedByDescending { it.addedAt }
    }

    override suspend fun touch(id: Long, lastSeen: Long) {
        rows[id]?.let { rows[id] = it.copy(lastSeen = lastSeen) }
    }
}

/**
 * Per spec §8b, the desktop originates the pairing nonce: it generates its own Ed25519 keypair
 * and a random nonce, shows both (plus its name) as a QR, and the phone's `approveScanned` scans
 * that QR directly — no prior `beginPairing()` handshake step exists on the phone side anymore.
 */
class PairingServiceTest {

    private lateinit var dao: FakeAllowedDeviceDao
    private var clock = 1_000_000_000L
    private lateinit var service: PairingService
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var privateKey: Ed25519PrivateKeyParameters
    private lateinit var pubkeyB64: String

    @Before
    fun setUp() {
        dao = FakeAllowedDeviceDao()
        service = PairingService(dao, now = { clock }, randomNonce = { UUID.randomUUID().toString() })

        privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        pubkeyB64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
    }

    private fun sign(message: ByteArray, key: Ed25519PrivateKeyParameters = privateKey): String {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    private fun qrPayload(nonce: String, pubkey: String = pubkeyB64, name: String = "Laptop") =
        json.encodeToString(QrPayload.serializer(), QrPayload(pubkey, name, nonce))

    // ---- approveScanned ----------------------------------------------------

    @Test
    fun `approveScanned with a well-formed payload and no prior beginPairing inserts the device and returns a confirm code`() =
        runTest {
            val approved = service.approveScanned(qrPayload("desktop-nonce-1"))

            assertEquals(pubkeyB64, approved.pubkey)
            assertEquals("Laptop", approved.name)
            assertTrue(approved.confirmCode.isNotBlank())

            val stored = dao.byPubkey(pubkeyB64)
            assertNotNull(stored)
            assertFalse(stored!!.revoked)
        }

    @Test
    fun `approveScanned rejects a malformed payload`() = runTest {
        try {
            service.approveScanned("not-real-json")
            fail("expected malformed payload to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNull(dao.byPubkey(pubkeyB64))
    }

    // ---- completePairingRequest ----------------------------------------------------

    @Test
    fun `completePairingRequest with no prior approval fails`() = runTest {
        try {
            service.completePairingRequest(pubkeyB64, "desktop-nonce-1")
            fail("expected completePairingRequest to fail with no approval on record")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `completePairingRequest after approval returns fingerprint and the same confirm code`() = runTest {
        service.setCertFingerprint("AB:CD:EF")
        val approved = service.approveScanned(qrPayload("desktop-nonce-1"))

        val result = service.completePairingRequest(pubkeyB64, "desktop-nonce-1")

        assertEquals("AB:CD:EF", result.certFingerprint)
        assertEquals(approved.confirmCode, result.confirmCode)
    }

    @Test
    fun `completePairingRequest with the wrong nonce fails`() = runTest {
        service.approveScanned(qrPayload("desktop-nonce-1"))

        try {
            service.completePairingRequest(pubkeyB64, "wrong-nonce")
            fail("expected wrong nonce to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `completePairingRequest replaying a consumed nonce fails`() = runTest {
        service.approveScanned(qrPayload("desktop-nonce-1"))
        service.completePairingRequest(pubkeyB64, "desktop-nonce-1")

        try {
            service.completePairingRequest(pubkeyB64, "desktop-nonce-1")
            fail("expected replayed nonce to be rejected")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `completePairingRequest fails once the approval has expired`() = runTest {
        service.approveScanned(qrPayload("desktop-nonce-1"))
        clock += 5 * 60 * 1000L + 1 // one ms past the 5-minute approval TTL

        try {
            service.completePairingRequest(pubkeyB64, "desktop-nonce-1")
            fail("expected expired approval to be rejected")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `confirm code is derived deterministically from the nonce`() = runTest {
        val approvedA = service.approveScanned(qrPayload("nonce-a", pubkey = pubkeyB64))
        val otherKey = Ed25519PrivateKeyParameters(SecureRandom())
        val otherPubkey = Base64.getEncoder().encodeToString(otherKey.generatePublicKey().encoded)
        val approvedB = service.approveScanned(qrPayload("nonce-b", pubkey = otherPubkey))

        assertNotEquals(approvedA.confirmCode, approvedB.confirmCode)
    }

    // ---- issueSession ----------------------------------------------------

    @Test
    fun `issueSession returns a token for an allowed device with a valid signature`() = runTest {
        dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        val challenge = service.newChallenge()

        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))

        assertNotNull(token)
        assertTrue(service.validateSession(token!!))
    }

    @Test
    fun `issueSession returns null for a wrong signature`() = runTest {
        dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        val challenge = service.newChallenge()
        val otherKey = Ed25519PrivateKeyParameters(SecureRandom())

        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8), otherKey))

        assertNull(token)
    }

    @Test
    fun `issueSession returns null for a revoked device`() = runTest {
        val id = dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        dao.setRevoked(id, true)
        val challenge = service.newChallenge()

        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))

        assertNull(token)
    }

    @Test
    fun `issueSession returns null for an unknown pubkey`() = runTest {
        val challenge = service.newChallenge()

        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))

        assertNull(token)
    }

    // ---- validateSession ----------------------------------------------------

    @Test
    fun `validateSession is false for garbage or unissued tokens`() = runTest {
        dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        val challenge = service.newChallenge()
        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))!!

        assertTrue(service.validateSession(token))
        assertFalse(service.validateSession("garbage-token"))
        assertFalse(service.validateSession(""))
    }

    // ---- single-use challenge ----------------------------------------------------

    @Test
    fun `challenge is single-use, consumed by issueSession`() = runTest {
        dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        val challenge = service.newChallenge()
        val signature = sign(challenge.toByteArray(Charsets.UTF_8))

        val first = service.issueSession(pubkeyB64, challenge, signature)
        assertNotNull(first)

        // Replaying the exact same (challenge, signature) pair must fail: the challenge was
        // already consumed by the first call.
        val second = service.issueSession(pubkeyB64, challenge, signature)
        assertNull(second)
    }

    @Test
    fun `revoke prevents subsequent session issuance`() = runTest {
        val id = dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        service.revoke(id)
        val challenge = service.newChallenge()

        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))

        assertNull(token)
    }

    // ---- revoke immediacy ----------------------------------------------------

    @Test
    fun `revoke invalidates an already-issued session immediately`() = runTest {
        val id = dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        val challenge = service.newChallenge()
        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))!!
        assertTrue(service.validateSession(token))

        service.revoke(id)

        assertFalse(
            "a revoked device's previously-issued session token must stop validating immediately, " +
                "not merely expire after the session TTL",
            service.validateSession(token),
        )
    }

    // ---- TOCTOU: concurrent completePairingRequest on the same approved pairing ----------------

    @Test
    fun `concurrent completePairingRequest calls let exactly one succeed`() {
        kotlinx.coroutines.runBlocking { service.approveScanned(qrPayload("desktop-nonce-1")) }

        val successes = java.util.concurrent.atomic.AtomicInteger(0)
        // A loser can observe either "pairing nonce already used" (it saw the record before a
        // winner's consumed flag got swept) or "no pairing in progress" (the winner's now-consumed
        // record was already evicted by sweepApprovedPairings() by the time this call looked).
        // Both are equivalent from every caller's point of view — PairingRoutes maps every
        // IllegalStateException from this call to the same 202 "pending" response — so the
        // eviction of consumed entries (hygiene fix) doesn't change externally observable
        // behavior; only which of these two equivalent internal messages a loser sees.
        val expectedLoserMessages = setOf("pairing nonce already used", "no pairing in progress")
        val loserFailures = java.util.concurrent.atomic.AtomicInteger(0)
        val unexpectedFailures = java.util.concurrent.atomic.AtomicInteger(0)

        // Real threads (not coroutines sharing one dispatcher) to actually create the race that
        // exposed the bug: many callers hitting completePairingRequest for the same approved
        // pairing at once, as could plausibly happen under Ktor's multithreaded request dispatcher.
        val threads = (1..20).map {
            Thread {
                kotlinx.coroutines.runBlocking {
                    try {
                        service.completePairingRequest(pubkeyB64, "desktop-nonce-1")
                        successes.incrementAndGet()
                    } catch (e: IllegalStateException) {
                        if (e.message in expectedLoserMessages) {
                            loserFailures.incrementAndGet()
                        } else {
                            unexpectedFailures.incrementAndGet()
                        }
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("exactly one caller must win the race", 1, successes.get())
        assertEquals(0, unexpectedFailures.get())
        assertEquals(19, loserFailures.get())
    }

    // ---- lazy eviction of stale entries ----------------------------------------------------

    @Test
    fun `stale approved pairings are evicted once expired and a later call touches the map`() = runTest {
        service.approveScanned(qrPayload("desktop-nonce-1"))
        assertEquals(1, service.approvedPairingsSizeForTest())

        clock += 5 * 60 * 1000L + 1 // past the approval TTL

        // A second, unrelated call that also locks approvedPairings should sweep the stale entry.
        val otherKey = Ed25519PrivateKeyParameters(SecureRandom())
        val otherPubkey = Base64.getEncoder().encodeToString(otherKey.generatePublicKey().encoded)
        service.approveScanned(qrPayload("desktop-nonce-2", pubkey = otherPubkey))

        // Only the fresh entry should remain; the expired one was swept.
        assertEquals(1, service.approvedPairingsSizeForTest())

        // Behaviorally: the expired pairing can no longer be completed.
        try {
            service.completePairingRequest(pubkeyB64, "desktop-nonce-1")
            fail("expected the swept, expired pairing to no longer be completable")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `consumed approved pairings are evicted on the next mutating call`() = runTest {
        service.approveScanned(qrPayload("desktop-nonce-1"))
        service.completePairingRequest(pubkeyB64, "desktop-nonce-1")
        assertEquals(1, service.approvedPairingsSizeForTest())

        val otherKey = Ed25519PrivateKeyParameters(SecureRandom())
        val otherPubkey = Base64.getEncoder().encodeToString(otherKey.generatePublicKey().encoded)
        service.approveScanned(qrPayload("desktop-nonce-2", pubkey = otherPubkey))

        assertEquals(1, service.approvedPairingsSizeForTest())
    }

    @Test
    fun `stale sessions are evicted once expired and a later call touches the map`() = runTest {
        dao.insert(AllowedDeviceEntity(name = "Laptop", pubkey = pubkeyB64, addedAt = clock, lastSeen = null))
        val challenge = service.newChallenge()
        val token = service.issueSession(pubkeyB64, challenge, sign(challenge.toByteArray(Charsets.UTF_8)))!!
        assertEquals(1, service.sessionsSizeForTest())

        clock += 24 * 60 * 60 * 1000L + 1 // past the session TTL

        assertFalse(service.validateSession(token))
        assertEquals(0, service.sessionsSizeForTest())
    }

    @Test
    fun `stale challenges are evicted once expired and a later call touches the map`() = runTest {
        service.newChallenge()
        assertEquals(1, service.challengesSizeForTest())

        clock += 2 * 60 * 1000L + 1 // past the challenge TTL

        service.newChallenge()

        // Only the fresh challenge should remain; the expired one was swept.
        assertEquals(1, service.challengesSizeForTest())
    }

    // ---- re-pairing a known device (Fix 2) ----------------------------------------------------

    @Test
    fun `approveScanned for an already-paired device re-pairs cleanly instead of throwing`() = runTest {
        val first = service.approveScanned(qrPayload("desktop-nonce-1"))

        // Re-scan (e.g. desktop lost its pairing state and re-shows a fresh QR for the same key).
        val second = service.approveScanned(qrPayload("desktop-nonce-2"))

        assertEquals(first.id, second.id)
        assertEquals(pubkeyB64, second.pubkey)
        assertTrue(second.confirmCode.isNotBlank())

        // The fresh nonce is the one that can complete the pairing now.
        val result = service.completePairingRequest(pubkeyB64, "desktop-nonce-2")
        assertEquals(second.confirmCode, result.confirmCode)

        assertEquals(1, service.listDevices().size)
    }

    @Test
    fun `approveScanned for a previously-revoked device un-revokes it and pairing completes`() = runTest {
        val first = service.approveScanned(qrPayload("desktop-nonce-1"))
        service.revoke(first.id)
        assertTrue(dao.byPubkey(pubkeyB64)!!.revoked)

        val second = service.approveScanned(qrPayload("desktop-nonce-2"))

        assertEquals(first.id, second.id)
        assertFalse(dao.byPubkey(pubkeyB64)!!.revoked)

        val result = service.completePairingRequest(pubkeyB64, "desktop-nonce-2")
        assertEquals(second.confirmCode, result.confirmCode)
    }
}
