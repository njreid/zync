package dev.njr.zync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.njr.zync.ZyncApp
import java.util.concurrent.TimeUnit

/**
 * Syncs the phone's op log with the central server on a connectivity-gated schedule.
 * A no-op until the phone is paired. Failures retry with backoff (transient network).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as ZyncApp).syncOnce()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

/** Enqueues sync work: a one-shot after local mutations + a periodic connectivity sweep. */
object SyncScheduler {
    private const val ONESHOT = "zync-sync-now"
    private const val PERIODIC = "zync-sync-periodic"

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Request a prompt sync (e.g. after a capture/mutation), when connectivity allows. */
    fun requestSync(context: Context) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, work)
    }

    /** Ensure a periodic background sync is scheduled. */
    fun schedulePeriodic(context: Context) {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
