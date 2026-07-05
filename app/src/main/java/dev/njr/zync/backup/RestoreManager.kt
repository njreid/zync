package dev.njr.zync.backup

import android.content.Context
import android.util.Log

class RestoreManager(
    private val context: Context,
    private val settings: BackupSettings,
    private val manager: BackupManager = BackupWorker.backupManager(context),
    private val drive: DriveClient = GoogleDriveClient(context),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun restoreIfRequested(): Boolean {
        if (!settings.state().restorePending) return false
        return try {
            val restored = BackupRunner(
                manager = manager,
                drive = drive,
                passphraseProvider = { settings.passphrase() },
                now = now,
            ).restoreLatest()
            if (!restored) error("no backup available to restore")
            settings.clearRestoreRequest()
            true
        } catch (t: Throwable) {
            Log.e("zync", "startup restore failed", t)
            settings.recordFailure(now(), t.message ?: "restore failed")
            false
        }
    }
}
