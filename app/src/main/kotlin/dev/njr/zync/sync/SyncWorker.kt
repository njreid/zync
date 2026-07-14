package dev.njr.zync.sync

import android.content.Context
import android.util.Log
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
 * A no-op until the phone is paired. Transient failures (network, 5xx, rate limit)
 * retry with backoff; permanent request failures (auth, malformed) fail the work item
 * instead of retrying the same doomed request forever.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as ZyncApp).syncOnce()
        Result.success()
    } catch (e: dev.njr.zync.replica.SyncRequestException) {
        if (e.permanent) {
            Log.w("zync", "sync failed permanently (HTTP ${e.status}); not retrying", e)
            Result.failure()
        } else {
            Result.retry()
        }
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
    fun requestSync(context: Context) = ifWorkManager(context) { wm ->
        val work = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build()
        wm.enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, work)
    }

    /** Ensure a periodic background sync is scheduled. */
    fun schedulePeriodic(context: Context) = ifWorkManager(context) { wm ->
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    /**
     * WorkManager auto-initializes in production (its `androidx.startup` provider ships with
     * `work-runtime`), but not under Robolectric unit tests that don't wire it up — so skip
     * (rather than crash app startup / a capture) when it isn't available.
     */
    private inline fun ifWorkManager(context: Context, block: (WorkManager) -> Unit) {
        runCatching { WorkManager.getInstance(context) }
            .onSuccess(block)
            .onFailure { Log.w("zync", "WorkManager unavailable; sync not scheduled", it) }
    }
}
