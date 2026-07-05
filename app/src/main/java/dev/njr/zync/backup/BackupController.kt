package dev.njr.zync.backup

import android.content.Context

class BackupController(
    private val context: Context,
    private val settings: BackupSettings,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun state(): BackupState = settings.state()

    fun configure(enabled: Boolean, passphrase: String?) {
        passphrase?.takeIf { it.isNotBlank() }?.let { settings.savePassphrase(it.toCharArray()) }
        if (enabled && settings.passphrase() == null) error("backup passphrase required")
        settings.setEnabled(enabled)
        BackupScheduler.schedulePeriodic(context)
        if (enabled) BackupScheduler.requestBackupSoon(context)
    }

    fun requestRestore(): BackupState {
        if (settings.passphrase() == null) error("backup passphrase required")
        settings.requestRestore()
        return settings.state()
    }

    suspend fun backupNow(): BackupState {
        return BackupService(
            manager = BackupWorker.backupManager(context),
            drive = GoogleDriveClient(context),
            settings = settings,
            now = now,
        ).runBackup()
    }

}
