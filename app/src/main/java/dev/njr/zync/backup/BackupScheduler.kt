package dev.njr.zync.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Schedules automatic backups via WorkManager. Two triggers make backups
 * "hands-off":
 *
 *  - [schedulePeriodic] — a daily safety-net run (idempotent; call on app start).
 *  - [requestBackupSoon] — enqueued when the data changes; a short initial delay
 *    plus `REPLACE` on a unique name means a burst of edits collapses into a
 *    single backup a couple minutes after the last change (debounce).
 *
 * Both run only on a network connection with the battery not low, so backups
 * never surprise the user's data/battery.
 */
object BackupScheduler {
    const val PERIODIC_WORK = "zync-backup-periodic"
    const val SOON_WORK = "zync-backup-soon"

    private val SOON_DELAY: Duration = Duration.ofMinutes(2)
    private val PERIOD: Duration = Duration.ofDays(1)

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun requestBackupSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(SOON_DELAY)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SOON_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(SOON_WORK)
    }
}
