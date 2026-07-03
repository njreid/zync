package dev.njr.zync.pairing

/**
 * Protects a keystore password at rest. The persisted server keystore ([ServerCertStore]) is
 * itself just an app-private file, so its password can't sit next to it in plaintext — that would
 * mean anyone who can read one file can read both. Production wraps the password with an
 * AES-GCM key held in the Android Keystore ([AndroidKeystorePasswordProtector]); tests inject a
 * fake, since real Android Keystore crypto only means something on a real device/emulator
 * (Task 8 instrumented tests), not under Robolectric.
 */
interface PasswordProtector {
    /** Encrypts [plain] for storage. The returned bytes are opaque to [unprotect]'s caller. */
    fun protect(plain: CharArray): ByteArray

    /** Inverse of [protect]. */
    fun unprotect(protected: ByteArray): CharArray
}
