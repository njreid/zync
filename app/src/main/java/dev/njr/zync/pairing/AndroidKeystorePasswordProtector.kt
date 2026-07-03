package dev.njr.zync.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [PasswordProtector]: wraps the plaintext keystore password with an AES-GCM key that
 * never leaves the Android Keystore (`AndroidKeyStore` provider) — the key material itself is
 * hardware-backed where available and is never exposed to the app process, so an attacker who
 * only obtains the app's private files (e.g. via a backup or a rooted-device file read) gets
 * neither the plaintext password nor a key capable of deriving it off-device.
 *
 * Storage format: `[4-byte IV length][IV][GCM ciphertext+tag]`.
 */
class AndroidKeystorePasswordProtector(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : PasswordProtector {

    private val androidKeyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun protect(plain: CharArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(String(plain).toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun unprotect(protected: ByteArray): CharArray {
        val buffer = ByteBuffer.wrap(protected)
        val ivLength = buffer.int
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val key = androidKeyStore.getKey(keyAlias, null) as? SecretKey
            ?: error("missing Android Keystore key '$keyAlias' — was protect() ever called on this install?")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8).toCharArray()
    }

    private fun getOrCreateKey(): SecretKey {
        (androidKeyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val DEFAULT_KEY_ALIAS = "zync-server-password-key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
