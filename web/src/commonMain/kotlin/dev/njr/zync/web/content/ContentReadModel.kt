package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A node as the shared UI reads it — folded from the op-log projection. */
data class NodeView(
    val id: Ulid,
    val kind: String?,
    val title: String?,
    val notes: String?,
    val status: String?,
    val deferUntil: Long?,
    val dueDate: Long?,
    val person: String?,
    /** OCR lifecycle on a scanned/photo attachment: PENDING/RUNNING/DONE/FAILED. */
    val ocrStatus: String?,
    /** `blob-<sha256>` key of the OCR text, once it has landed. */
    val ocrBlobHash: String?,
    /** Operator-written document summary, once summarize has run. */
    val summary: String?,
    val parent: Ulid?,
    val tags: Set<Ulid>,
    val alive: Boolean,
    /** Agent-authored, awaiting human review — surfaced only in the proposals panel. */
    val proposed: Boolean = false,
)

/** A context/tag as the UI reads it. */
data class ContextView(val id: Ulid, val name: String?)

/**
 * Reads for the shared content UI, folded from the `core` projection over a [StateStore].
 * The single read surface for both the server and the phone.
 */
class ContentReadModel(private val store: StateStore) {
    private fun snapshots() = store.project().values.filter { it.alive }

    /**
     * Live task/project children of [parent] (null = root), title-sorted. Excludes
     * comments, agent-flow machinery kinds, and unreviewed proposals — those surface
     * in [comments] / [proposals] instead.
     */
    fun children(parent: Ulid?): List<NodeView> =
        snapshots()
            .filter {
                it.kind() != "context" && it.kind() != "comment" &&
                    it.kind() !in AgentFlow.INTERNAL_KINDS && !it.proposed() &&
                    it.parent?.toString() == parent?.toString()
            }
            .map { it.toView() }
            .sortedBy { it.title ?: "" }

    /** Comments/annotations under [node], oldest first (unreviewed proposals + trashed excluded). */
    fun comments(node: Ulid): List<NodeView> =
        snapshots()
            .filter { it.kind() == "comment" && !it.proposed() && it.parent?.toString() == node.toString() }
            .map { it.toView() }
            .filter { it.status != "DROPPED" }
            .sortedBy { it.id.toString() }

    /** Agent-authored nodes awaiting human review (accept/reject), oldest first. */
    fun proposals(): List<NodeView> =
        snapshots().filter { it.proposed() }.map { it.toView() }.sortedBy { it.id.toString() }

    /** Inbox: live children of [inbox] that aren't completed/dropped/deferred-out. */
    fun inbox(inbox: Ulid?, now: Long = Long.MAX_VALUE): List<NodeView> =
        children(inbox).filter {
            it.status != "DONE" && it.status != "DROPPED" && (it.deferUntil == null || it.deferUntil <= now)
        }

    fun node(id: Ulid): NodeView? = store.project()[id]?.takeIf { it.alive }?.toView()

    /** All live task/project nodes. */
    fun nodes(): List<NodeView> = snapshots().filter { it.kind() != "context" }.map { it.toView() }

    /** All live contexts (tags). */
    fun contexts(): List<ContextView> =
        snapshots().filter { it.kind() == "context" }
            .map { ContextView(it.entityId, it.fields["name"].asString()) }
            .sortedBy { it.name ?: "" }

    /** All live, active, non-deferred tasks (any context) — the Next pool. */
    fun activeTasks(now: Long = Long.MAX_VALUE): List<NodeView> =
        snapshots()
            .filter { it.kind() == "task" && !it.proposed() }
            .map { it.toView() }
            .filter { it.status != "DONE" && it.status != "DROPPED" && (it.deferUntil == null || it.deferUntil <= now) }
            .sortedBy { it.title ?: "" }

    /** Live active tasks waiting on a person (the Waiting tile). */
    fun waitingTasks(now: Long = Long.MAX_VALUE): List<NodeView> =
        activeTasks(now).filter { it.person != null }

    /** Live tasks due at/before [byMillis] (incl. overdue), soonest first — the Today view. */
    fun dueTasks(byMillis: Long): List<NodeView> =
        snapshots()
            .filter { it.kind() == "task" && !it.proposed() }
            .map { it.toView() }
            .filter { it.status != "DONE" && it.status != "DROPPED" && it.dueDate != null && it.dueDate <= byMillis }
            .sortedBy { it.dueDate }

    /** Live projects — the move targets for organizing tasks into the tree. */
    fun projects(): List<NodeView> =
        snapshots().filter { it.kind() == "project" && !it.proposed() }
            .map { it.toView() }
            .filter { it.status != "DROPPED" }
            .sortedBy { it.title ?: "" }

    /**
     * The context view (launcher spec L4, v0.2 semantics): live, active, non-deferred
     * TASKS across the whole tree tagged with [context] — a flat next-actions list.
     */
    fun contextTasks(context: Ulid, now: Long = Long.MAX_VALUE): List<NodeView> =
        snapshots()
            .filter { it.kind() == "task" && !it.proposed() && it.tags.any { t -> t.toString() == context.toString() } }
            .map { it.toView() }
            .filter { it.status != "DONE" && it.status != "DROPPED" && (it.deferUntil == null || it.deferUntil <= now) }
            .sortedBy { it.title ?: "" }

    private fun EntitySnapshot.kind(): String? = fields["kind"].asString()

    private fun EntitySnapshot.proposed(): Boolean =
        (fields[AgentFlow.FIELD_PROPOSED] as? JsonPrimitive)?.content == "true"

    private fun EntitySnapshot.toView() = NodeView(
        id = entityId,
        kind = fields[Fields.KIND].asString(),
        title = fields[Fields.TITLE].asString(),
        notes = fields[Fields.NOTES].asString(),
        status = fields[Fields.STATUS].asString(),
        deferUntil = (fields[Fields.DEFER_UNTIL] as? JsonPrimitive)?.content?.toLongOrNull(),
        dueDate = (fields[Fields.DUE_DATE] as? JsonPrimitive)?.content?.toLongOrNull(),
        person = fields[Fields.PERSON].asString(),
        ocrStatus = fields[Fields.OCR_STATUS].asString(),
        ocrBlobHash = fields[Fields.OCR_BLOB_HASH].asString(),
        summary = fields[Fields.SUMMARY].asString(),
        parent = parent,
        tags = tags,
        alive = alive,
        proposed = proposed(),
    )

    // JsonNull IS a JsonPrimitive (content "null") — cleared fields must read as absent.
    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
