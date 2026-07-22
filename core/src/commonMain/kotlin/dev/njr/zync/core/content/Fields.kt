package dev.njr.zync.core.content

import dev.njr.zync.core.id.Ulid

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

    /** Fetched <title> of a shared URL (preview shown when an inbox item is expanded). */
    const val LINK_TITLE = "linkTitle"

    /** First text paragraph fetched from a shared URL (preview shown when expanded). */
    const val LINK_PREVIEW = "linkPreview"

    /**
     * Fractional-index string giving a node a total order *among its siblings*
     * (GTD triage spec §3): drives FIFO inbox reorder, project reorder, and
     * loose-task reorder. Absent = unranked; unranked siblings sort by ULID
     * ascending (capture order = FIFO for free). See [FractionalIndex].
     */
    const val RANK = "rank"

    /** Coarse effort size (GTD triage §4): one of [Size]. Bigger ⇒ must become a project. */
    const val SIZE = "size"

    /**
     * Operator-owned JSON array of ranked file-location proposals for an inbox item
     * (GTD triage §6). Each element `{"targetId","title","tree","score"}`; empty/absent
     * = no confident suggestion. Accepting a chip is the human Move; owner Operator("suggest-file").
     */
    const val FILE_SUGGESTIONS = "fileSuggestions"

    /**
     * Operator-owned single Reference-tree node id proposed as the filing parent for a
     * DONE task (GTD triage §7, RESOLVED Q5). Accepting = Move under it + status FILED;
     * owner Operator("auto-file-done").
     */
    const val PROPOSED_FILE_PARENT = "proposedFileParent"

    // --- Suggestion nodes (external-op-api spec §4): a bot's proposed edit to a field on an
    // existing node. kind="suggestion", proposed=true; accepting emits the real SetField. ---
    /** The node the suggestion targets. */
    const val TARGET_ID = "targetId"
    /** The field on [TARGET_ID] the suggestion proposes to change. */
    const val TARGET_FIELD = "targetField"
    /** The proposed new value for [TARGET_FIELD] (any JSON value). */
    const val PROPOSED_VALUE = "proposedValue"
}

/** The node kind used by external-op-api suggestion nodes (§4). */
const val KIND_SUGGESTION = "suggestion"

/** OCR lifecycle values for [Fields.OCR_STATUS]. */
object OcrStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

/** GTD status values for [Fields.STATUS] (spec §1: extends ACTIVE/DONE/DROPPED with WAITING/FILED). */
object Status {
    const val ACTIVE = "ACTIVE"
    const val WAITING = "WAITING"
    const val DONE = "DONE"
    const val DROPPED = "DROPPED"

    /** Moved into the Reference tree: archived, out of active lists, still searchable. */
    const val FILED = "FILED"
}

/** Coarse effort sizes for [Fields.SIZE] (GTD triage §4): S <2m, M <30m, L <~2h; no XL. */
object Size {
    const val S = "S"
    const val M = "M"
    const val L = "L"
    val ALL = listOf(S, M, L)
}

/** Well-known node ids threaded as config (like the inbox root), not stored as fields. */
object WellKnownNodes {
    /** Parent of the Reference tree (GTD triage §7); filing Moves under this subtree. */
    val REFERENCE_ROOT: Ulid = Ulid.parse("000000000000000000000RFRNC")
}
