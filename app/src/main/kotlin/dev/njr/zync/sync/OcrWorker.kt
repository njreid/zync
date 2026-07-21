package dev.njr.zync.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.njr.zync.ZyncApp
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.replica.OcrProcessor
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val KEY_NODE = "node"
private const val KEY_BLOB = "blob"
private const val KEY_MIME = "mime"

/**
 * Runs the async OCR leg for one scanned/photo document on a connectivity-gated,
 * retrying schedule (spec 2026-07-16-scan-ocr-summary §2). The scan itself already
 * landed as an attachment; this worker adds the OCR text + status. All logic lives
 * in [OcrProcessor] (WorkManager-free, unit-tested); this is the thin adapter.
 */
class OcrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ZyncApp
        val nodeStr = inputData.getString(KEY_NODE) ?: return Result.failure()
        val blob = inputData.getString(KEY_BLOB) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "application/pdf"
        val nodeId = runCatching { Ulid.parse(nodeStr) }.getOrNull() ?: return Result.failure()

        return when (app.ocrProcessor.process(nodeId, blob, mime)) {
            OcrProcessor.Outcome.DONE -> {
                // Push the OCR text blob + ops (blob-before-op handles ordering).
                SyncScheduler.requestSync(applicationContext)
                Result.success()
            }
            OcrProcessor.Outcome.FAILED -> Result.success() // recorded FAILED; don't retry
            OcrProcessor.Outcome.RETRY -> Result.retry()
        }
    }
}

/** Enqueues per-document OCR work, plus a backfill sweep for scans missing OCR. */
object OcrScheduler {
    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueue OCR for a freshly-captured scan/photo ([scanBlobHash] = the stored bytes). */
    fun enqueue(context: Context, nodeId: Ulid, scanBlobHash: String, sourceMime: String) = ifWorkManager(context) { wm ->
        val work = OneTimeWorkRequestBuilder<OcrWorker>()
            .setConstraints(connected)
            .setInputData(workDataOf(KEY_NODE to nodeId.toString(), KEY_BLOB to scanBlobHash, KEY_MIME to sourceMime))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // KEEP: a scan already queued/running is not re-enqueued (idempotent backfill).
        wm.enqueueUniqueWork("zync-ocr-$nodeId", ExistingWorkPolicy.KEEP, work)
    }

    /** Backfill OCR for scans captured before OCR existed (or that never finished). */
    fun enqueueMissing(app: ZyncApp) {
        runCatching { OcrProcessor.pendingScans(app.opStore) }
            .onSuccess { refs -> refs.forEach { enqueue(app, it.nodeId, it.scanBlobHash, it.sourceMime) } }
            .onFailure { Log.w("zync", "OCR backfill scan failed", it) }
    }

    /** Media type for Drive's OCR upload, from the capture extension. */
    fun mimeForExtension(extension: String): String = when (extension.lowercase(Locale.US)) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/pdf"
    }

    private inline fun ifWorkManager(context: Context, block: (WorkManager) -> Unit) {
        runCatching { WorkManager.getInstance(context) }
            .onSuccess(block)
            .onFailure { Log.w("zync", "WorkManager unavailable; OCR not scheduled", it) }
    }
}
