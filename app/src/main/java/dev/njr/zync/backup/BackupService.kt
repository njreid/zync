package dev.njr.zync.backup

class BackupService(
    private val manager: BackupManager,
    private val drive: DriveClient,
    private val settings: BackupSettings,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun runBackup(): BackupState {
        if (!settings.state().enabled) error("backup is disabled")
        try {
            when (
                val result = BackupRunner(
                    manager = manager,
                    drive = drive,
                    passphraseProvider = { settings.passphrase() },
                    now = now,
                ).backupNow()
            ) {
                BackupRunResult.Skipped -> error("backup passphrase required")
                is BackupRunResult.Uploaded -> settings.recordSuccess(now(), result.name)
            }
        } catch (t: Throwable) {
            settings.recordFailure(now(), t.message ?: "backup failed")
            throw t
        }
        return settings.state()
    }
}
