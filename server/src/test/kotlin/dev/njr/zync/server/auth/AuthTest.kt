package dev.njr.zync.server.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class AuthTest {
    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val pub = Ed25519.publicKeyFor(seed)
    private val now = 1_000_000L
    private val window = 300_000L

    private fun sign(method: String, path: String, ts: Long, nonce: String): String =
        Base64.encode(Ed25519.sign(seed, SignedRequestVerifier.canonicalString(method, path, ts, nonce).encodeToByteArray()))

    private fun verifier(registry: DeviceRegistry, nonces: NonceCache = NonceCache(window)) =
        SignedRequestVerifier(registry, nonces, windowMillis = window)

    private fun registryWithPhone() = InMemoryDeviceRegistry().apply { register("phone", pub) }

    // --- Ed25519 ---
    @Test
    fun ed25519RoundTripsAndRejectsTampering() {
        val msg = "hello".encodeToByteArray()
        val sig = Ed25519.sign(seed, msg)
        assertTrue(Ed25519.verify(pub, msg, sig))
        assertFalse(Ed25519.verify(pub, "hell0".encodeToByteArray(), sig))
        val otherPub = Ed25519.publicKeyFor(ByteArray(32) { (it + 9).toByte() })
        assertFalse(Ed25519.verify(otherPub, msg, sig))
    }

    // --- SignedRequestVerifier ---
    @Test
    fun validDeviceAccepted() {
        val v = verifier(registryWithPhone())
        val result = v.verify("GET", "/sync/pull", "phone", now, "n1", sign("GET", "/sync/pull", now, "n1"), now)
        assertEquals(AuthResult.Authorized("phone"), result)
    }

    @Test
    fun unknownDeviceRejected() {
        val v = verifier(InMemoryDeviceRegistry())
        val r = v.verify("GET", "/sync/pull", "ghost", now, "n1", sign("GET", "/sync/pull", now, "n1"), now)
        assertIs<AuthResult.Rejected>(r)
    }

    @Test
    fun revokedDeviceRejected() {
        val registry = registryWithPhone().apply { revoke("phone") }
        val r = verifier(registry).verify("GET", "/sync/pull", "phone", now, "n1", sign("GET", "/sync/pull", now, "n1"), now)
        assertEquals(AuthResult.Rejected("revoked device"), r)
    }

    @Test
    fun staleTimestampRejected() {
        val v = verifier(registryWithPhone())
        val old = now - window - 1
        val r = v.verify("GET", "/sync/pull", "phone", old, "n1", sign("GET", "/sync/pull", old, "n1"), now)
        assertEquals(AuthResult.Rejected("stale timestamp"), r)
    }

    @Test
    fun badSignatureRejected() {
        val v = verifier(registryWithPhone())
        // signature for a different path than the one presented
        val r = v.verify("GET", "/sync/pull", "phone", now, "n1", sign("GET", "/sync/push", now, "n1"), now)
        assertEquals(AuthResult.Rejected("bad signature"), r)
    }

    @Test
    fun replayedNonceRejected() {
        val v = verifier(registryWithPhone())
        val sig = sign("GET", "/sync/pull", now, "n1")
        assertIs<AuthResult.Authorized>(v.verify("GET", "/sync/pull", "phone", now, "n1", sig, now))
        assertEquals(AuthResult.Rejected("replayed nonce"), v.verify("GET", "/sync/pull", "phone", now, "n1", sig, now))
    }

    // --- SessionStore ---
    @Test
    fun sessionLifecycle() {
        var counter = 0
        val store = SessionStore(ttlMillis = 100, tokenGenerator = { "tok${counter++}" })
        val token = store.mint(now)
        assertTrue(store.validate(token, now))
        assertFalse(store.validate(token, now + 101), "expired session must be invalid")

        val fresh = store.mint(now)
        store.logout(fresh)
        assertFalse(store.validate(fresh, now))
    }
}
