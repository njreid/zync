package dev.njr.zync.pairing

import io.ktor.network.tls.certificates.buildKeyStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.security.auth.x500.X500Principal
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.util.Base64

/**
 * Identity material for the local pairing/sync server: a PKCS12 keystore (as raw bytes, ready
 * to persist to disk or hand to Ktor's Netty `sslConnector`), plus the SHA-256 fingerprint of
 * the leaf certificate for display/verification during device pairing.
 */
data class ServerIdentity(
    val keyStoreBytes: ByteArray,
    val keyStorePassword: CharArray,
    val keyAlias: String,
    val certFingerprintSha256: String,
)

/**
 * Crypto primitives backing device pairing:
 *  - Ed25519 signature verification (BouncyCastle; Android's built-in providers don't reliably
 *    support Ed25519 across API levels).
 *  - Self-signed server certificate generation, via Ktor's `buildKeyStore` (pairs natively with
 *    the Netty `sslConnector` used to serve HTTPS).
 *  - Certificate fingerprinting and constant-time string comparison for pairing-code checks.
 */
object Crypto {

    private const val KEY_ALIAS = "zync-server"

    // buildKeyStore defaults to a 3-day validity, which is far too short for a server identity
    // that a paired device needs to keep trusting across sessions. 10 years is effectively
    // "as long as this install lives" for a local pairing/sync server.
    private const val CERT_VALIDITY_DAYS = 3650L

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Verifies an Ed25519 signature over [message] using a base64-encoded raw public key
     * ([pubkeyB64]) and a base64-encoded signature ([signatureB64]).
     *
     * Never throws: any malformed base64, malformed key, malformed signature, or verification
     * failure results in `false`.
     */
    fun verifyEd25519(pubkeyB64: String, message: ByteArray, signatureB64: String): Boolean {
        return try {
            val pubkeyBytes = Base64.getDecoder().decode(pubkeyB64)
            val signatureBytes = Base64.getDecoder().decode(signatureB64)

            val publicKey = Ed25519PublicKeyParameters(pubkeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a self-signed server certificate + keypair using Ktor's `buildKeyStore`, and
     * serializes it to PKCS12 bytes for persistence.
     *
     * [password] protects the PKCS12 keystore. It defaults to a fresh [randomPassword] per call —
     * a hardcoded password would mean anyone who ever obtained one persisted keystore file could
     * decrypt any zync install's keystore, so callers that persist the returned identity (see
     * `ServerCertStore`) must hang onto the returned `keyStorePassword` (itself protected at rest)
     * rather than relying on a well-known constant.
     *
     * Fingerprint format: SHA-256 over the certificate's DER encoding, rendered as uppercase hex
     * octets separated by colons (e.g. "AB:CD:EF:..."), matching the format shown in the pairing
     * UI.
     */
    fun generateSelfSignedCert(cn: String = "zync", password: CharArray = randomPassword()): ServerIdentity {
        val keyStore = buildKeyStore {
            certificate(KEY_ALIAS) {
                this.password = String(password)
                domains = listOf("127.0.0.1", "0.0.0.0")
                subject = X500Principal("CN=$cn, OU=zync, O=zync")
                daysValid = CERT_VALIDITY_DAYS
            }
        }

        val cert = keyStore.getCertificate(KEY_ALIAS)
        val fingerprint = sha256Fingerprint(cert.encoded)

        val output = ByteArrayOutputStream()
        keyStore.store(output, password)

        return ServerIdentity(
            keyStoreBytes = output.toByteArray(),
            keyStorePassword = password.copyOf(),
            keyAlias = KEY_ALIAS,
            certFingerprintSha256 = fingerprint,
        )
    }

    /** A fresh, per-call random password suitable for protecting a PKCS12 keystore. */
    fun randomPassword(lengthBytes: Int = 32): CharArray {
        val bytes = ByteArray(lengthBytes)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes).toCharArray()
    }

    /** SHA-256 over [certDer], rendered as uppercase colon-separated hex (e.g. "AB:CD:..."). */
    fun sha256Fingerprint(certDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certDer)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    /** Constant-time equality of two strings, compared as UTF-8 bytes. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
