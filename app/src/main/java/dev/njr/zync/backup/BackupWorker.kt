package dev.njr.zync.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.njr.zync.attach.AttachmentStore
import java.util.concurrent.TimeUnit

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
        const val PERIODIC_WORK = "zync-backup-periodic"
        const val DEBOUNCED_WORK = "zync-backup-debounced"

        fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequest.Builder(BackupWorker::class.java, 1, TimeUnit.DAYS)
                .setConstraints(backupConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        fun debouncedRequest(): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(BackupWorker::class.java)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setConstraints(backupConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        fun backupManager(context: Context): BackupManager =
            BackupManager(
                dbFile = context.getDatabasePath("zync.db"),
                attachmentRoot = AttachmentStore.defaultRoot(context),
                beforeSnapshot = { checkpointDatabase(context) },
            )

        private fun checkpointDatabase(context: Context) {
            val dbFile = context.getDatabasePath("zync.db")
            if (!dbFile.isFile) return
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        }

        private fun backupConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
    }
}

internal fun Throwable.isUserActionRequiredBackupFailure(): Boolean =
    message == "Google Drive is not connected" ||
        message == "Google Drive authorization needs user approval" ||
        message == "backup passphrase required"

class BackupScheduler(private val workManager: WorkManager) {
    fun ensurePeriodicBackup() {
        workManager.enqueueUniquePeriodicWork(
            BackupWorker.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            BackupWorker.periodicRequest(),
        )
    }

    fun enqueueDebouncedBackup() {
        workManager.enqueueUniqueWork(
            BackupWorker.DEBOUNCED_WORK,
            ExistingWorkPolicy.REPLACE,
            BackupWorker.debouncedRequest(),
        )
    }
}
