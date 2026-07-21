package dev.njr.zync.replica

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.OcrStatus
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.data.AttachmentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The device-independent OCR pipeline for one scanned/photo document (spec
 * 2026-07-16-scan-ocr-summary §2), factored out of [dev.njr.zync.sync.OcrWorker]
 * so it is fully testable off a real WorkManager: read the scan blob → OCR via a
 * [DriveOcr] transport → store the text as an `ocr_text` blob + attachment (which
 * the existing blob-before-op sync uploads) → set `ocrBlobHash` + `ocrStatus` on
 * the node (the `ocrBlobHash` field is what triggers the server `summarize`
 * operator). Status transitions PENDING→RUNNING→DONE/FAILED are node SetFields.
 */
class OcrProcessor(
    private val blobs: LocalBlobStore,
    private val opWriter: OpWriter,
    private val drive: DriveOcr,
    private val onChanged: () -> Unit = {},
) {
    /** What the worker should do with the WorkManager result. */
    enum class Outcome { DONE, RETRY, FAILED }

    suspend fun process(nodeId: Ulid, scanBlobHash: String, sourceMime: String): Outcome {
        val bytes = blobs.get(scanBlobHash)
        if (bytes == null) {
            // The scan bytes vanished (should not happen) — nothing to OCR.
            markStatus(nodeId, OcrStatus.FAILED)
            return Outcome.FAILED
        }

        markStatus(nodeId, OcrStatus.RUNNING)

        val text = try {
            drive.ocr(bytes, sourceMime)
        } catch (e: DriveOcrException) {
            return if (e.permanent) {
                markStatus(nodeId, OcrStatus.FAILED)
                Outcome.FAILED
            } else {
                // Leave status RUNNING; WorkManager retries with backoff.
                Outcome.RETRY
            }
        }

        val ocrHash = blobs.put(text.toByteArray())
        // Pair the text with an ocr_text attachment so the blob rides the existing
        // blob-before-op upload; setting ocrBlobHash then triggers summarize server-side.
        opWriter.createAttachment(nodeId, AttachmentType.OCR_TEXT.name.lowercase(Locale.US), ocrHash, "ocr.txt")
        opWriter.setField(nodeId, Fields.OCR_BLOB_HASH, JsonPrimitive(ocrHash))
        markStatus(nodeId, OcrStatus.DONE)
        return Outcome.DONE
    }

    private fun markStatus(nodeId: Ulid, status: String) {
        opWriter.setField(nodeId, Fields.OCR_STATUS, JsonPrimitive(status))
        onChanged()
    }

    /** A scanned document awaiting OCR (its node id + the scan blob to feed Drive). */
    data class ScanRef(val nodeId: Ulid, val scanBlobHash: String, val sourceMime: String)

    companion object {
        // Mirrors core's internal ATTACHMENT_FIELD (dev.njr.zync.core.merge.Apply).
        private const val ATTACHMENT_FIELD = "@attachment"

        /**
         * Scanned/photo attachments whose node has no OCR status yet — the
         * retroactive backfill set (scans captured before OCR existed). Source
         * mime is unknown here, so it defaults to PDF (Drive still OCRs images);
         * live captures pass the exact mime.
         */
        fun pendingScans(store: StateStore): List<ScanRef> {
            val snapshots = store.project()
            return snapshots.values.mapNotNull { snap ->
                val payload = snap.fields[ATTACHMENT_FIELD] as? JsonObject ?: return@mapNotNull null
                if ((payload["type"] as? JsonPrimitive)?.content != AttachmentType.PDF.name.lowercase(Locale.US)) {
                    return@mapNotNull null
                }
                val nodeStr = (payload["nodeId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val blobHash = (payload["blobHash"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val nodeId = runCatching { Ulid.parse(nodeStr) }.getOrNull() ?: return@mapNotNull null
                val node = snapshots[nodeId] ?: return@mapNotNull null
                if (node.fields[Fields.OCR_STATUS] != null) return@mapNotNull null // already handled/queued
                ScanRef(nodeId, blobHash, "application/pdf")
            }
        }
    }
}
