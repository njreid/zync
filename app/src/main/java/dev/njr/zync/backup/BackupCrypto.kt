package dev.njr.zync.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupCrypto(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "backup passphrase required" }
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val payload = EncryptedPayload(
            version = FORMAT_VERSION,
            kdf = "PBKDF2WithHmacSHA256",
            iterations = iterations,
            salt = salt,
            iv = iv,
            ciphertext = cipher.doFinal(plain),
        )
        return Json.encodeToString(EncryptedPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
    }

    fun decrypt(encrypted: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "backup passphrase required" }
        val payload = Json.decodeFromString(
            EncryptedPayload.serializer(),
            encrypted.toString(Charsets.UTF_8),
        )
        require(payload.version == FORMAT_VERSION) { "unsupported backup format ${payload.version}" }
        require(payload.kdf == "PBKDF2WithHmacSHA256") { "unsupported backup KDF ${payload.kdf}" }
        val key = deriveKey(passphrase, payload.salt, payload.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload.iv))
        return cipher.doFinal(payload.ciphertext)
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also(random::nextBytes)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, rounds: Int = iterations): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, rounds, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val DEFAULT_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val RAW_KEY_IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128

        fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
            require(key.size == 32) { "backup key must be 32 bytes (AES-256)" }
            val iv = ByteArray(RAW_KEY_IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
            return iv + cipher.doFinal(plaintext)
        }

        fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
            require(key.size == 32) { "backup key must be 32 bytes (AES-256)" }
            require(blob.size > RAW_KEY_IV_BYTES) { "backup blob is too short to contain an IV" }
            val iv = blob.copyOfRange(0, RAW_KEY_IV_BYTES)
            val ciphertext = blob.copyOfRange(RAW_KEY_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
            return cipher.doFinal(ciphertext)
        }

        fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}

@Serializable
private data class EncryptedPayload(
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)
