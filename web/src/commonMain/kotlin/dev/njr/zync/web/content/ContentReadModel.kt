package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.FractionalIndex
import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.Status
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
    /** Fractional-index sibling order (GTD triage §3); null = unranked (sorts by ULID/FIFO). */
    val rank: String? = null,
    /** Coarse effort size (GTD triage §4): S/M/L or null. */
    val size: String? = null,
    /** Operator-suggested file locations for an inbox item (GTD triage §6); empty until #6. */
    val fileSuggestions: List<FileSuggestion> = emptyList(),
    /** Reference node proposed as the filing parent for a DONE task (GTD triage §7); null = none. */
    val proposedFileParent: Ulid? = null,
)

/** One ranked file-location proposal for an inbox item (GTD triage §6). */
data class FileSuggestion(val targetId: Ulid, val title: String, val tree: String, val score: Double)

/** An attachment as the triage preview reads it (GTD triage §4). */
data class AttachmentView(val id: Ulid, val type: String?, val filename: String?, val blobHash: String?)

/** A context/tag as the UI reads it. */
data class ContextView(val id: Ulid, val name: String?)

/** A one-slot reorder within a sibling list (GTD triage §3, spec Q2 = buttons for v1). */
enum class Reorder { UP, DOWN, TOP }

/**
 * One Next-surface row (spec §5): an [action] plus the [project] it belongs to.
 * [project] == null ⇒ the top loose root action; non-null ⇒ that project's first
 * completable action (one row per project).
 */
data class NextRow(val action: NodeView, val project: NodeView?)

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

    /**
     * Inbox: live children of [inbox] that aren't completed/dropped/deferred-out,
     * in **triage order** (GTD §3): by fractional-index `rank`, falling back to ULID
     * for unranked items so capture order is FIFO for free; ties break by ULID.
     */
    fun inbox(inbox: Ulid?, now: Long = Long.MAX_VALUE): List<NodeView> =
        children(inbox)
            .filter {
                it.status != "DONE" && it.status != "DROPPED" && (it.deferUntil == null || it.deferUntil <= now)
            }
            .sortedWith(compareBy({ it.effectiveRank() }, { it.id.toString() }))

    /**
     * The new `rank` to move [id] one slot toward [direction] within its inbox list,
     * or null if it's a no-op (already at the edge, or not in the list). The caller
     * writes it via [ContentCommands.setRank]. Reorder targets the displayed inbox
     * order, so it works whether neighbours are ranked or still FIFO.
     */
    fun reorderRank(inbox: Ulid?, id: Ulid, direction: Reorder, now: Long = Long.MAX_VALUE): String? {
        val items = inbox(inbox, now)
        val i = items.indexOfFirst { it.id.toString() == id.toString() }
        if (i < 0) return null
        return when (direction) {
            Reorder.TOP -> if (i == 0) null
                else FractionalIndex.between(null, items[0].effectiveRank())
            Reorder.UP -> if (i == 0) null
                else FractionalIndex.between(items.getOrNull(i - 2)?.effectiveRank(), items[i - 1].effectiveRank())
            Reorder.DOWN -> if (i == items.lastIndex) null
                else FractionalIndex.between(items[i + 1].effectiveRank(), items.getOrNull(i + 2)?.effectiveRank())
        }
    }

    private fun NodeView.effectiveRank(): String = rank ?: id.toString().lowercase()

    /**
     * Next Action list for context [context] (null = "any"), spec §5. Returns the top
     * loose root action (project == null) followed by each project's first completable
     * action (one [NextRow] per project). [inbox] is excluded so untriaged items don't
     * leak in. Excludes WAITING/DONE/DROPPED/FILED, defer-hidden, and person-delegated
     * (today's WAITING bridge). Ordering ([nextOrder]) is rank-based with dueDate/size
     * bumps (RESOLVED Q3); degrades to rank+dueDate when no sizes are set (build #4).
     */
    fun nextActions(context: Ulid?, inbox: Ulid? = null, now: Long = Long.MAX_VALUE): List<NextRow> {
        val order = nextOrder()
        val byId = snapshots().associate { it.entityId.toString() to it.toView() }

        val candidates = snapshots()
            .filter { it.kind() == "task" && !it.proposed() }
            .map { it.toView() }
            .filter { completableNow(it, context, now) }
            .filter { inbox == null || it.parent?.toString() != inbox.toString() }

        val loose = candidates
            .filter { it.parent == null }
            .minWithOrNull(order)
            ?.let { NextRow(it, null) }

        val perProject = candidates
            .filter { it.parent != null }
            .groupBy { it.parent!!.toString() }
            .mapNotNull { (pid, actions) ->
                val first = actions.minWithOrNull(order) ?: return@mapNotNull null
                NextRow(first, byId[pid])
            }
            .sortedWith(compareBy({ it.project?.effectiveRank() ?: "" }, { it.project?.id?.toString() ?: "" }))

        return listOfNotNull(loose) + perProject
    }

    /** Completable in [context] now: ACTIVE, not deferred, not delegated, tag-matched. */
    private fun completableNow(n: NodeView, context: Ulid?, now: Long): Boolean =
        n.status != Status.WAITING && n.status != Status.DONE &&
            n.status != Status.DROPPED && n.status != Status.FILED &&
            n.person == null &&
            (n.deferUntil == null || n.deferUntil <= now) &&
            (context == null || n.tags.any { it.toString() == context.toString() })

    /**
     * Next-action priority (RESOLVED Q3 = rank + dueDate/size). Ascending = higher
     * priority: dueDate (soonest first, undated last) → size (S<M<L, absent neutral) →
     * manual rank → ULID. All-absent sizes ⇒ the size term is constant ⇒ pure rank+dueDate.
     */
    private fun nextOrder(): Comparator<NodeView> = compareBy(
        { it.dueDate ?: Long.MAX_VALUE },
        { sizeOrder(it.size) },
        { it.effectiveRank() },
        { it.id.toString() },
    )

    private fun sizeOrder(size: String?): Int = when (size) {
        Size.S -> 0
        Size.L -> 2
        else -> 1 // M and absent are the neutral middle
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

    /** Attachments linked to [node] (payload.nodeId == node), for triage preview (spec §4). */
    fun attachments(node: Ulid): List<AttachmentView> =
        store.project().values
            .filter { it.alive }
            .mapNotNull { snap ->
                val payload = snap.fields["@attachment"] as? JsonObject ?: return@mapNotNull null
                if ((payload["nodeId"] as? JsonPrimitive)?.content != node.toString()) return@mapNotNull null
                AttachmentView(
                    id = snap.entityId,
                    type = (payload["type"] as? JsonPrimitive)?.content,
                    filename = (payload["relativePath"] as? JsonPrimitive)?.content,
                    blobHash = (payload["blobHash"] as? JsonPrimitive)?.content,
                )
            }
            .sortedBy { it.id.toString() }

    /** 1-based depth: a root-parented node = 1, its child = 2, … Cycle-guarded, bounded. */
    fun depthOf(id: Ulid, max: Int = 8): Int {
        var depth = 1 // the node itself sits at level 1 when root-parented
        var current = store.project()[id]?.parent
        val seen = HashSet<String>()
        while (current != null && depth <= max + 2 && seen.add(current.toString())) {
            depth++
            current = store.project()[current]?.parent
        }
        return depth
    }

    /** Tallest descendant chain below [id]; a leaf = 0. Bounded to guard corrupt cycles. */
    fun subtreeHeight(id: Ulid, budget: Int = 12): Int {
        if (budget <= 0) return 0
        val kids = children(id)
        if (kids.isEmpty()) return 0
        return 1 + kids.maxOf { subtreeHeight(it.id, budget - 1) }
    }

    /** True iff moving [node] under [newParent] would push any node past [max] levels (spec §8, Q8). */
    fun moveWouldExceedDepth(node: Ulid, newParent: Ulid, max: Int = 4): Boolean =
        depthOf(newParent) + 1 + subtreeHeight(node) > max

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
        rank = fields[Fields.RANK].asString(),
        size = fields[Fields.SIZE].asString(),
        fileSuggestions = parseFileSuggestions(fields[Fields.FILE_SUGGESTIONS]),
        proposedFileParent = fields[Fields.PROPOSED_FILE_PARENT].asString()
            ?.let { runCatching { Ulid.parse(it) }.getOrNull() },
    )

    /** Parse the operator-written `fileSuggestions` JSON array; malformed/absent → empty. */
    private fun parseFileSuggestions(value: JsonElement?): List<FileSuggestion> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val target = (obj["targetId"] as? JsonPrimitive)?.content?.let { runCatching { Ulid.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            FileSuggestion(
                targetId = target,
                title = (obj["title"] as? JsonPrimitive)?.content ?: "",
                tree = (obj["tree"] as? JsonPrimitive)?.content ?: "",
                score = (obj["score"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    // JsonNull IS a JsonPrimitive (content "null") — cleared fields must read as absent.
    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
