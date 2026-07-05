package dev.njr.zync.backup

import android.content.Context
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
    override suspend fun doWork(): Result =
        Result.failure()

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
            )

        private fun backupConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
    }
}

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
