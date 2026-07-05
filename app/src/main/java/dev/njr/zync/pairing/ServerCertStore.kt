package dev.njr.zync.pairing

import java.io.File
import java.security.KeyStore
import java.util.logging.Logger

/**
 * Persists the server's TLS identity (PKCS12 keystore bytes + its random per-install password,
 * the latter protected at rest via [protector]) to app-private storage, so the server's cert
 * fingerprint — and therefore a paired desktop's TLS pin — stays stable across app restarts
 * instead of being regenerated (and re-pairing required) every launch.
 *
 * Both files live under [filesDir] (`Context.filesDir`), which is private to the app on all
 * supported Android versions — never external/shared storage.
 */
class ServerCertStore(
    private val filesDir: File,
    private val protector: PasswordProtector,
) {
    private val keystoreFile: File get() = File(filesDir, KEYSTORE_FILENAME)
    private val passwordFile: File get() = File(filesDir, PASSWORD_FILENAME)

    /** The persisted identity, or `null` if none has been generated yet. */
    fun load(): ServerIdentity? {
        if (!keystoreFile.exists() || !passwordFile.exists()) return null

        val keyStoreBytes = keystoreFile.readBytes()
        val password = protector.unprotect(passwordFile.readBytes())

        val keyStore = KeyStore.getInstance(
            Crypto.KEYSTORE_TYPE,
            org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME,
        ).apply {
            load(keyStoreBytes.inputStream(), password)
        }
        val alias = keyStore.aliases().toList().first { keyStore.isKeyEntry(it) }
        val certificate = keyStore.getCertificate(alias)

        // A keystore persisted by an older build can carry a cert that no longer meets the current
        // strength policy (pre-2048 code emitted RSA-1024, which rustls/aws-lc-rs on the desktop
        // rejects outright — so an in-place update would silently keep serving a cert that can
        // never complete a TLS handshake). Discard such a persisted identity and its password so
        // the caller (loadOrCreate) regenerates a fresh, strong one. This invalidates any existing
        // pairing, which is acceptable pre-production.
        if (!Crypto.meetsKeyStrengthPolicy(certificate.publicKey)) {
            LOG.warning(
                "Discarding persisted server cert: key no longer meets strength policy " +
                    "(${certificate.publicKey.algorithm}); regenerating. Existing pairings are invalidated.",
            )
            keystoreFile.delete()
            passwordFile.delete()
            return null
        }

        val fingerprint = Crypto.sha256Fingerprint(certificate.encoded)

        return ServerIdentity(
            keyStoreBytes = keyStoreBytes,
            keyStorePassword = password,
            keyAlias = alias,
            certFingerprintSha256 = fingerprint,
        )
    }

    /** Writes [identity] to app-private storage, overwriting any previously persisted identity. */
    fun save(identity: ServerIdentity) {
        keystoreFile.writeBytes(identity.keyStoreBytes)
        passwordFile.writeBytes(protector.protect(identity.keyStorePassword))
    }

    /**
     * The persisted identity if one exists; otherwise generates a fresh one (via [generate], a
     * fresh random password by default) and persists it before returning. Never regenerates an
     * already-persisted identity — that's what keeps the fingerprint stable across restarts.
     */
    fun loadOrCreate(
        generate: (password: CharArray) -> ServerIdentity = { password -> Crypto.generateSelfSignedCert(password = password) },
    ): ServerIdentity {
        load()?.let { return it }

        val identity = generate(Crypto.randomPassword())
        save(identity)
        return identity
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(ServerCertStore::class.java.name)
        const val KEYSTORE_FILENAME = "zync-server.p12"
        const val PASSWORD_FILENAME = "zync-server.p12.pw"
    }
}
