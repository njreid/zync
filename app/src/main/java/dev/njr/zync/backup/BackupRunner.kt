package dev.njr.zync.backup

class BackupRunner(
    private val manager: BackupManager,
    private val drive: DriveClient,
    private val passphraseProvider: suspend () -> CharArray?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun backupNow(): BackupRunResult {
        val passphrase = passphraseProvider() ?: return BackupRunResult.Skipped
        val name = "zync-${now()}.zyncbackup"
        val encrypted = manager.createEncryptedBackup(passphrase)
        drive.uploadBackup(name, encrypted)
        return BackupRunResult.Uploaded(name)
    }

    suspend fun restoreLatest(): Boolean {
        val passphrase = passphraseProvider() ?: return false
        val encrypted = drive.latestBackup() ?: return false
        manager.restoreEncryptedBackup(encrypted, passphrase)
        return true
    }
}

sealed class BackupRunResult {
    data object Skipped : BackupRunResult()
    data class Uploaded(val name: String) : BackupRunResult()
}
