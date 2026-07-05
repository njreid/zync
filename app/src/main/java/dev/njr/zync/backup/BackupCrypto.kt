package dev.njr.zync.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption for backup blobs and manifests. Output
 * layout is `iv(12) || ciphertext+tag`. A fresh random IV per call means the
 * remote store never sees repeated ciphertext, and the GCM tag makes tampering
 * (or a wrong key) fail loudly on decrypt.
 *
 * The 32-byte key is held by the caller (in production, wrapped in the Android
 * Keystore — the device layer). Dedup is done on the *plaintext* SHA-256 (see
 * [SnapshotBackup]), so random-IV encryption does not defeat it: an
 * already-stored blob is simply not re-uploaded.
 */
object BackupCrypto {
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "backup key must be 32 bytes (AES-256)" }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "backup key must be 32 bytes (AES-256)" }
        require(blob.size > IV_LENGTH) { "backup blob is too short to contain an IV" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    /** Generate a new random 256-bit backup key. */
    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
