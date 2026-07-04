package dev.njr.zync.pairing

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.experimental.xor

class CryptoTest {

    @Test
    fun verifyEd25519_acceptsValidSignature() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()
        val message = "hello zync".toByteArray(Charsets.UTF_8)

        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()

        val pubkeyB64 = Base64.getEncoder().encodeToString(publicKey.encoded)
        val signatureB64 = Base64.getEncoder().encodeToString(signature)

        assertTrue(Crypto.verifyEd25519(pubkeyB64, message, signatureB64))
    }

    @Test
    fun verifyEd25519_rejectsTamperedSignature() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()
        val message = "hello zync".toByteArray(Charsets.UTF_8)

        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()
        // Flip a byte in the signature to tamper it.
        signature[0] = signature[0].xor(0x01)

        val pubkeyB64 = Base64.getEncoder().encodeToString(publicKey.encoded)
        val signatureB64 = Base64.getEncoder().encodeToString(signature)

        assertFalse(Crypto.verifyEd25519(pubkeyB64, message, signatureB64))
    }

    @Test
    fun verifyEd25519_rejectsTamperedMessage() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()
        val message = "hello zync".toByteArray(Charsets.UTF_8)

        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()

        val pubkeyB64 = Base64.getEncoder().encodeToString(publicKey.encoded)
        val signatureB64 = Base64.getEncoder().encodeToString(signature)
        val tamperedMessage = "hello Zync".toByteArray(Charsets.UTF_8)

        assertFalse(Crypto.verifyEd25519(pubkeyB64, tamperedMessage, signatureB64))
    }

    @Test
    fun verifyEd25519_returnsFalseOnGarbageInput_neverThrows() {
        assertFalse(Crypto.verifyEd25519("not-valid-base64!!!", "msg".toByteArray(), "also-not-valid!!!"))
        assertFalse(Crypto.verifyEd25519("", ByteArray(0), ""))
        assertFalse(Crypto.verifyEd25519(Base64.getEncoder().encodeToString(ByteArray(3)), "msg".toByteArray(), Base64.getEncoder().encodeToString(ByteArray(3))))
    }

    @Test
    fun selfSignedCert_loadsIntoKeyStore_andFingerprintIsStable() {
        val id = Crypto.generateSelfSignedCert()

        // Deliberately use the exact same type+provider the app uses everywhere (generation,
        // ServerCertStore.load, RemoteAccessManager.enable's sslConnector keystore) rather than
        // KeyStore.getInstance("PKCS12") with the platform-default provider. This is what pins
        // the regression from m1c-task-8: ktor's buildKeyStore used to persist via
        // KeyStore.getDefaultType(), which is "PKCS12" on a desktop JVM but "BKS" on real
        // Android, while callers hardcoded "PKCS12" to read it back — invisible here on the JVM
        // precisely because the JVM's default happens to already be PKCS12. Explicitly requesting
        // the BC provider (as the app now does end-to-end) removes the platform-default
        // dependency entirely.
        val keyStore = KeyStore.getInstance(Crypto.KEYSTORE_TYPE, "BC")
        keyStore.load(id.keyStoreBytes.inputStream(), id.keyStorePassword)

        assertTrue(keyStore.isKeyEntry(id.keyAlias))
        val cert = keyStore.getCertificate(id.keyAlias)
        assertNotNull(cert)

        val recomputed = Crypto.sha256Fingerprint(cert.encoded)
        assertEquals(id.certFingerprintSha256, recomputed)

        // Fingerprint format: hex, colon-separated, uppercase, 32 bytes -> 95 chars.
        assertTrue(recomputed.matches(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")))
    }

    @Test
    fun selfSignedCert_roundTripsThroughServerCertStore_usingAppCodePath() {
        // Exercises the SAME path the app uses on real Android: generate -> ServerCertStore.save
        // (raw bytes to disk) -> a fresh ServerCertStore.load() (fresh KeyStore.getInstance call,
        // just like a fresh process would do) -> the private key entry Netty's sslConnector needs.
        // This is the regression test for the BKS/PKCS12 type-mismatch bug: it round-trips through
        // real persisted bytes rather than reusing the in-memory KeyStore object that generated
        // them, which is exactly what the previous ktor-buildKeyStore-based implementation would
        // have failed on Android (though not on the JVM, where the default type is already
        // PKCS12 — hence why this needs the type to be pinned explicitly, not merely "whatever
        // the platform default is", to actually protect against the regression).
        val tmpDir = kotlin.io.path.createTempDirectory(prefix = "zync-certstore-test").toFile()
        try {
            val store = ServerCertStore(tmpDir, NoopPasswordProtector())
            val generated = store.loadOrCreate()

            val reloaded = ServerCertStore(tmpDir, NoopPasswordProtector()).load()
            assertNotNull(reloaded)
            reloaded!!

            assertEquals(generated.certFingerprintSha256, reloaded.certFingerprintSha256)
            assertEquals(generated.keyAlias, reloaded.keyAlias)

            // The exact same type+provider RemoteAccessManager.enable() uses for the sslConnector.
            val keyStore = KeyStore.getInstance(Crypto.KEYSTORE_TYPE, "BC")
            keyStore.load(reloaded.keyStoreBytes.inputStream(), reloaded.keyStorePassword)
            assertTrue(keyStore.isKeyEntry(reloaded.keyAlias))
            assertNotNull(keyStore.getKey(reloaded.keyAlias, reloaded.keyStorePassword))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** No-op protector so this JVM test doesn't depend on AndroidKeyStore being available. */
    private class NoopPasswordProtector : PasswordProtector {
        override fun protect(plain: CharArray): ByteArray =
            String(plain).toByteArray(Charsets.UTF_8)

        override fun unprotect(protected: ByteArray): CharArray =
            String(protected, Charsets.UTF_8).toCharArray()
    }

    @Test
    fun selfSignedCert_generatesDistinctKeysEachCall() {
        val id1 = Crypto.generateSelfSignedCert()
        val id2 = Crypto.generateSelfSignedCert()
        assertFalse(id1.certFingerprintSha256 == id2.certFingerprintSha256)
    }

    @Test
    fun sha256Fingerprint_isDeterministic() {
        val data = "some cert bytes".toByteArray(Charsets.UTF_8)
        assertEquals(Crypto.sha256Fingerprint(data), Crypto.sha256Fingerprint(data))
    }

    @Test
    fun constantTimeEquals_matchesSemantics() {
        assertTrue(Crypto.constantTimeEquals("abc123", "abc123"))
        assertFalse(Crypto.constantTimeEquals("abc123", "abc124"))
        assertFalse(Crypto.constantTimeEquals("abc123", "abc12"))
        assertFalse(Crypto.constantTimeEquals("abc123", ""))
        assertTrue(Crypto.constantTimeEquals("", ""))

        // Sanity: should match MessageDigest.isEqual on UTF-8 bytes directly.
        val a = "fingerprint-value"
        val b = "fingerprint-value"
        assertEquals(
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8)),
            Crypto.constantTimeEquals(a, b),
        )
    }
}
