package dev.njr.zync.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun `encrypt then decrypt round-trips`() {
        val key = BackupCrypto.newKey()
        val plaintext = "the whole zync database".toByteArray()
        val blob = BackupCrypto.encrypt(plaintext, key)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(blob, key))
    }

    @Test
    fun `ciphertext differs from plaintext and repeats use fresh IVs`() {
        val key = BackupCrypto.newKey()
        val plaintext = "secret".toByteArray()
        val a = BackupCrypto.encrypt(plaintext, key)
        val b = BackupCrypto.encrypt(plaintext, key)
        assertFalse(a.contentEquals(plaintext))
        assertFalse("random IV should make repeats differ", a.contentEquals(b))
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val blob = BackupCrypto.encrypt("x".toByteArray(), BackupCrypto.newKey())
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(blob, BackupCrypto.newKey()) }
    }

    @Test
    fun `tampered ciphertext fails the GCM tag`() {
        val key = BackupCrypto.newKey()
        val blob = BackupCrypto.encrypt("payload".toByteArray(), key)
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(blob, key) }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt("x".toByteArray(), ByteArray(16))
        }
    }

    @Test
    fun `newKey yields distinct 32-byte keys`() {
        val a = BackupCrypto.newKey()
        val b = BackupCrypto.newKey()
        assertTrue(a.size == 32 && b.size == 32)
        assertFalse(a.contentEquals(b))
    }
}
