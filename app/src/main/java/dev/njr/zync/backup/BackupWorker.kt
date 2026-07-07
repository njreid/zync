package dev.njr.zync.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.njr.zync.ZyncApp
import dev.njr.zync.attach.AttachmentStore

class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = BackupSettings(applicationContext)
        if (!settings.state().enabled) return Result.success()

        return try {
            BackupService(
                manager = backupManager(applicationContext),
                drive = GoogleDriveClient(applicationContext),
                settings = settings,
            ).runBackup()
            Result.success()
        } catch (t: Throwable) {
            Log.e("zync", "automatic backup failed", t)
            if (t.isUserActionRequiredBackupFailure()) Result.failure() else Result.retry()
        }
    }

    companion object {
        fun backupManager(context: Context): BackupManager {
            val database = (context.applicationContext as? ZyncApp)?.database
            return BackupManager(
                dbFile = context.getDatabasePath("zync.db"),
                attachmentRoot = AttachmentStore.defaultRoot(context),
                // Checkpoint on Room's own connection so the snapshot copy is a
                // complete, self-consistent database (see DbSnapshot). Null only
                // in contexts without a ZyncApp (e.g. isolated tests), where the
                // copy falls back to the raw file.
                beforeSnapshot = { database?.let(DbSnapshot::checkpointForBackup) },
            )
        }
    }
}

internal fun Throwable.isUserActionRequiredBackupFailure(): Boolean =
    message == "Google Drive is not connected" ||
        message == "Google Drive authorization needs user approval" ||
        message == "backup passphrase required"
