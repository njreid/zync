package dev.njr.zync.backup

class BackupRunner(
    private val manager: BackupManager,
    private val drive: DriveClient,
    private val passphraseProvider: suspend () -> CharArray?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun backupNow(): Boolean {
        val passphrase = passphraseProvider() ?: return false
        val encrypted = manager.createEncryptedBackup(passphrase)
        drive.uploadBackup("zync-${now()}.zyncbackup", encrypted)
        return true
    }

    suspend fun restoreLatest(): Boolean {
        val passphrase = passphraseProvider() ?: return false
        val encrypted = drive.latestBackup() ?: return false
        manager.restoreEncryptedBackup(encrypted, passphrase)
        return true
    }
}
