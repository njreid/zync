package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.operator.ReadScopeHandle
import dev.njr.zync.core.state.EntitySnapshot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A resolved read-scope predicate (spec §7 open question: small named scopes,
 * not a second query engine). [reads] declares the register fields the
 * predicate consults *and* the fields fed to the LLM prompt — together they
 * are the operator's entire view of the entity, so they also define its
 * [dev.njr.zync.core.operator.InputVersion] and the edges used for static
 * cycle detection.
 */
class ReadScope(
    val ref: String,
    val reads: Set<String>,
    private val predicate: (EntitySnapshot) -> Boolean,
) {
    fun matches(snapshot: EntitySnapshot): Boolean = predicate(snapshot)
}

/** Maps a manifest's opaque [ReadScopeHandle] to a runtime [ReadScope]. */
class ReadScopeResolver(scopes: List<ReadScope>) {
    private val byRef = scopes.associateBy { it.ref }

    fun resolve(handle: ReadScopeHandle): ReadScope? = byRef[handle.ref]

    companion object {
        /** The built-in scopes every deployment knows. */
        fun default(): ReadScopeResolver =
            ReadScopeResolver(listOf(ReadScopes.inboxTask, ReadScopes.scannedDoc, ReadScopes.inboxTriage, ReadScopes.doneTask))
    }
}

/** Built-in named read scopes. */
object ReadScopes {
    const val INBOX_TASK_REF = "inbox-task"
    const val SCANNED_DOC_REF = "scanned-doc"
    const val INBOX_TRIAGE_REF = "inbox-triage"
    const val DONE_TASK_REF = "done-task"

    /**
     * The reference scope from the spec: `kind=task AND parent=INBOX AND tags=∅`.
     * The server's inbox is the root level (the web UI's inbox view lists
     * root-parented nodes), and only live, still-ACTIVE tasks qualify.
     */
    val inboxTask: ReadScope = ReadScope(
        ref = INBOX_TASK_REF,
        reads = setOf("kind", "status", "title", "notes"),
    ) { s ->
        s.alive &&
            s.parent == null &&
            s.tags.isEmpty() &&
            s.fields["kind"].asString() == "task" &&
            (s.fields["status"].asString() ?: "ACTIVE") == "ACTIVE"
    }

    /**
     * A scanned/photo document whose OCR text has landed: `ocrBlobHash` is set.
     * The operator reads the document title (context) and the OCR blob key, which
     * [OperatorPrompt] expands to the decoded text. Uses
     * [dev.njr.zync.core.operator.TriggerKind.EntityChangesInScope] so a re-scan
     * (new blob hash) re-summarizes, while redelivery of the same hash does not.
     */
    val scannedDoc: ReadScope = ReadScope(
        ref = SCANNED_DOC_REF,
        reads = setOf(Fields.OCR_BLOB_HASH, Fields.TITLE),
    ) { s ->
        s.alive && !s.fields[Fields.OCR_BLOB_HASH].asString().isNullOrBlank()
    }

    /** Inbox items awaiting a filing suggestion (GTD §6): root, still-ACTIVE task. */
    val inboxTriage: ReadScope = ReadScope(
        ref = INBOX_TRIAGE_REF,
        reads = setOf(Fields.KIND, Fields.STATUS, Fields.TITLE, Fields.NOTES, Fields.SUMMARY),
    ) { s ->
        s.alive && s.parent == null &&
            s.fields[Fields.KIND].asString() == "task" &&
            (s.fields[Fields.STATUS].asString() ?: "ACTIVE") == "ACTIVE"
    }

    /** A task just marked DONE (GTD §7): propose a Reference filing home. */
    val doneTask: ReadScope = ReadScope(
        ref = DONE_TASK_REF,
        reads = setOf(Fields.KIND, Fields.STATUS, Fields.TITLE, Fields.NOTES, Fields.SUMMARY),
    ) { s ->
        s.alive &&
            s.fields[Fields.KIND].asString() == "task" &&
            s.fields[Fields.STATUS].asString() == "DONE"
    }

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
