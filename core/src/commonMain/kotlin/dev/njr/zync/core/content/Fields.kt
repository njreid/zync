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

    /** Personal reading lifecycle for saved articles; deliberately independent of GTD status. */
    const val READING_STATE = "readingState"

    /** Last explicitly saved reader position as a whole-document percentage (0..100). */
    const val READING_PROGRESS = "readingProgress"

    /** The URL of a shared link (shown as an icon; the full URL is revealed in Edit). */
    const val LINK_URL = "linkUrl"

    /**
     * Prefix for free-form tags: each tag is its OWN boolean register field (`tag:<label>` = true),
     * so tags merge per-label (a human adding one and a bot adding another both stick), reusing the
     * SetField op + the human-beats-bot rule. Bots label items here so they can find relevant work.
     */
    const val FREE_TAG_PREFIX = "tag:"

    /**
     * Explicit marker (boolean register, `true`) that a node under [WellKnownNodes.REFERENCE_ROOT] is
     * a **folder** rather than an item — the one deliberate asymmetry with derived Projects. It lets
     * an *empty* folder still read as a folder (so "add child" is well-defined); `typeOf` under
     * Reference keys on this marker, not on has-children (see reference-and-archive spec).
     */
    const val FOLDER = "folder"

    /**
     * Prefix for a Task's Reference links (reference-and-archive spec): each linked URL is its OWN
     * boolean register field (`ref:<url>` = true), so links merge per-URL like free tags — two
     * devices adding different links both survive. The read model exposes the list via referenceLinks.
     */
    const val REF_LINK_PREFIX = "ref:"

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

/** Reading lifecycle values for [Fields.READING_STATE]. Missing means [UNREAD]. */
object ReadingState {
    const val UNREAD = "UNREAD"
    const val READING = "READING"
    const val FINISHED = "FINISHED"
    val ALL = setOf(UNREAD, READING, FINISHED)
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

    /**
     * Parent of the Projects tree (items→tasks/folders model): a content node's TYPE is derived
     * from its location — a leaf under here is a Task, a leaf with children is a Project (folder),
     * a top-level leaf is an Inbox Item, and anything under [REFERENCE_ROOT] is Reference.
     */
    val PROJECTS_ROOT: Ulid = Ulid.parse("00000000000000000000PRJCTS")

    /**
     * Parent of the Archive area (reference-and-archive spec), sibling to Projects/Reference: a
     * Project is archived by Moving it (and its subtree) under here. Anything under this root is
     * **inert** — excluded from Next/Today/context/notifications — but still searchable, and moving
     * it back under Projects makes it live again.
     */
    val ARCHIVE_ROOT: Ulid = Ulid.parse("00000000000000000000ARCHVE")

    /**
     * Sentinel "move to the top level" target: a Move whose `newParentId` is [ROOT] un-parents
     * the node (back to the Projects root / actionable top level). It is never a real node —
     * [reintegrateMoves] translates it to "no parent" rather than a child link.
     */
    val ROOT: Ulid = Ulid.parse("00000000000000000000000000")
}
