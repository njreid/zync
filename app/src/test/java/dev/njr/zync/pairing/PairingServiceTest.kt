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

    // ---- beginPairing ----------------------------------------------------

    @Test
    fun `beginPairing produces a nonce and a derived confirm code`() {
        val pending = service.beginPairing()
        assertTrue(pending.nonce.isNotBlank())
        assertTrue(pending.confirmCode.isNotBlank())
        assertEquals(clock + 2 * 60 * 1000L, pending.expiresAt)
    }

    @Test
    fun `beginPairing pending nonce expires after TTL`() = runTest {
        val pending = service.beginPairing()
        clock += 2 * 60 * 1000L + 1 // one ms past the 2-minute TTL

        try {
            service.approveScanned(qrPayload(pending.nonce))
            fail("expected expired nonce to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNull(dao.byPubkey(pubkeyB64))
    }

    // ---- approveScanned ----------------------------------------------------

    @Test
    fun `approveScanned with matching nonce inserts the device`() = runTest {
        val pending = service.beginPairing()

        val approved = service.approveScanned(qrPayload(pending.nonce))

        assertEquals(pubkeyB64, approved.pubkey)
        assertEquals("Laptop", approved.name)
        assertEquals(pending.confirmCode, approved.confirmCode)

        val stored = dao.byPubkey(pubkeyB64)
        assertNotNull(stored)
        assertFalse(stored!!.revoked)
    }

    @Test
    fun `approveScanned with wrong nonce throws and does not insert`() = runTest {
        service.beginPairing()

        try {
            service.approveScanned(qrPayload("not-the-real-nonce"))
            fail("expected wrong nonce to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNull(dao.byPubkey(pubkeyB64))
    }

    @Test
    fun `approveScanned with no pairing in progress throws`() = runTest {
        try {
            service.approveScanned(qrPayload("whatever"))
            fail("expected missing pairing to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- completePairingRequest ----------------------------------------------------

    @Test
    fun `completePairingRequest before approval fails`() = runTest {
        val pending = service.beginPairing()

        try {
            service.completePairingRequest(pubkeyB64, pending.nonce)
            fail("expected completePairingRequest to fail before phone approval")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `completePairingRequest after approval returns fingerprint and confirm code`() = runTest {
        service.setCertFingerprint("AB:CD:EF")
        val pending = service.beginPairing()
        service.approveScanned(qrPayload(pending.nonce))

        val result = service.completePairingRequest(pubkeyB64, pending.nonce)

        assertEquals("AB:CD:EF", result.certFingerprint)
        assertEquals(pending.confirmCode, result.confirmCode)
    }

    @Test
    fun `completePairingRequest replaying a consumed nonce fails`() = runTest {
        val pending = service.beginPairing()
        service.approveScanned(qrPayload(pending.nonce))
        service.completePairingRequest(pubkeyB64, pending.nonce)

        try {
            service.completePairingRequest(pubkeyB64, pending.nonce)
            fail("expected replayed nonce to be rejected")
        } catch (e: IllegalStateException) {
            // expected
        }
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
}
