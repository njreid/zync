package dev.njr.zync.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager entry point for an automatic backup: resolves a [BackupJob] via
 * [jobProvider] and runs it. Retries (with WorkManager's backoff) on failure.
 *
 * [jobProvider] is set once by the app when backup is configured (Drive store +
 * encryption key wired — the device layer). Until then it returns null and the
 * worker is a no-op success, so scheduling can be exercised (and tested) before
 * the Drive plumbing exists.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val job = jobProvider(applicationContext) ?: return Result.success()
        return try {
            job.run()
            Result.success()
        } catch (e: Throwable) {
            Result.retry()
        }
    }

    companion object {
        /** Supplies the configured backup job, or null if backup isn't set up yet. */
        @Volatile
        var jobProvider: (Context) -> BackupJob? = { null }
    }
}
