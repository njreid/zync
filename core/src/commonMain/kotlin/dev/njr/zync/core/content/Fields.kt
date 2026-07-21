package dev.njr.zync.core.content

/**
 * The shared field-name vocabulary for content nodes (review finding #13, scoped:
 * constants only, no domain framework). One definition for `core` consumers,
 * `:web` commands/read model, and capture — typos stop compiling.
 */
object Fields {
    const val KIND = "kind"
    const val TITLE = "title"
    const val NOTES = "notes"
    const val STATUS = "status"
    const val NAME = "name" // contexts

    /** Hide-until (GTD defer): epoch millis; task resurfaces at this time. */
    const val DEFER_UNTIL = "deferUntil"

    /** Hard due date: epoch millis pinned to UTC NOON of the due day (see DueDates). */
    const val DUE_DATE = "dueDate"

    /** A person's display name (any contact link stays device-local, never synced). */
    const val PERSON = "person"

    /**
     * OCR lifecycle on a scanned/photo attachment node (device-owned):
     * one of PENDING | RUNNING | DONE | FAILED, written by the phone OcrWorker.
     */
    const val OCR_STATUS = "ocrStatus"

    /**
     * Content-addressed `blob-<sha256>` key of the full OCR text (rides the blob
     * pipeline via a paired `ocr_text` attachment). Setting it triggers the
     * server `summarize` operator.
     */
    const val OCR_BLOB_HASH = "ocrBlobHash"

    /** Operator-owned one-paragraph summary of a document's OCR text. */
    const val SUMMARY = "summary"
}

/** OCR lifecycle values for [Fields.OCR_STATUS]. */
object OcrStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}
