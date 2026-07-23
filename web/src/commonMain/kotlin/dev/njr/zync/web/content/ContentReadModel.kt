package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.FractionalIndex
import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.Status
import dev.njr.zync.core.content.KIND_SUGGESTION
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.content.stringContent
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.RegisterKey
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
    /** Fetched preview of a shared URL: page title + first paragraph (shown when an item expands). */
    val linkTitle: String? = null,
    val linkPreview: String? = null,
    /** The URL of a shared link (shown as an icon; full URL revealed in Edit). */
    val linkUrl: String? = null,
    /** Free-form tags (mergeable per-label); how bots + humans label items. */
    val freeTags: List<String> = emptyList(),
)

/** One ranked file-location proposal for an inbox item (GTD triage §6). */
data class FileSuggestion(val targetId: Ulid, val title: String, val tree: String, val score: Double)

/** An attachment as the triage preview reads it (GTD triage §4). */
data class AttachmentView(val id: Ulid, val type: String?, val filename: String?, val blobHash: String?)

/** A bot-proposed field edit awaiting review (external-op-api §4): the diff + who proposed it. */
data class SuggestionView(
    val id: Ulid,
    val targetId: Ulid,
    val targetTitle: String?,
    val field: String,
    val currentValue: String?,
    val proposedValue: JsonElement,
    val byBot: String?,
)

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

    /** Agent-authored nodes awaiting human review (accept/reject), oldest first. Suggestion
     *  nodes (proposed field edits) render separately via [suggestions]. */
    fun proposals(): List<NodeView> =
        snapshots().filter { it.proposed() && it.kind() != KIND_SUGGESTION }
            .map { it.toView() }.sortedBy { it.id.toString() }

    /** Bot-proposed field edits awaiting review (external-op-api §4): the diff for each. */
    fun suggestions(): List<SuggestionView> {
        val snaps = store.project()
        return snaps.values.asSequence()
            .filter { it.alive && it.kind() == KIND_SUGGESTION && it.proposed() }
            .mapNotNull { snap ->
                val targetId = snap.fields[Fields.TARGET_ID].asString()
                    ?.let { runCatching { Ulid.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val field = snap.fields[Fields.TARGET_FIELD].asString() ?: return@mapNotNull null
                val proposed = snap.fields[Fields.PROPOSED_VALUE] ?: return@mapNotNull null
                val target = snaps[targetId]
                SuggestionView(
                    id = snap.entityId,
                    targetId = targetId,
                    targetTitle = target?.fields?.get(Fields.TITLE).asString(),
                    field = field,
                    currentValue = target?.fields?.get(field).asString(),
                    proposedValue = proposed,
                    byBot = (store.getRegister(RegisterKey(snap.entityId, Fields.TARGET_FIELD))?.actor as? Actor.Bot)?.id,
                )
            }
            .sortedBy { it.id.toString() }
            .toList()
    }

    /**
     * Inbox: live children of [inbox] that aren't completed/dropped/deferred-out,
     * in **triage order** (GTD §3): by fractional-index `rank`, falling back to ULID
     * for unranked items so capture order is FIFO for free; ties break by ULID.
     */
    fun inbox(inbox: Ulid?, now: Long = Long.MAX_VALUE): List<NodeView> =
        children(inbox)
            .filter {
                it.status != "DONE" && it.status != "DROPPED" && it.status != Status.FILED && (it.deferUntil == null || it.deferUntil <= now)
            }
            .sortedWith(compareBy({ it.effectiveRank() }, { it.id.toString() }))

    /**
     * The `rank` writes to move [id] one slot toward [direction] within its inbox list;
     * empty = no-op (already at the edge, or not present). The caller applies each via
     * [ContentCommands.setRank]. Normally a single `{id -> rank}`; if the two neighbours
     * bracketing the new slot have **collided ranks** (a cross-device merge tie — see
     * [FractionalIndex]), it rebalances the whole sibling list to fresh evenly-spaced
     * ranks instead of throwing on `between(equal, equal)`.
     */
    fun reorder(inbox: Ulid?, id: Ulid, direction: Reorder, now: Long = Long.MAX_VALUE): Map<Ulid, String> {
        val items = inbox(inbox, now).toMutableList()
        val i = items.indexOfFirst { it.id.toString() == id.toString() }
        if (i < 0) return emptyMap()
        val newIndex = when (direction) {
            Reorder.TOP -> 0
            Reorder.UP -> i - 1
            Reorder.DOWN -> i + 1
        }
        if (newIndex == i || newIndex < 0 || newIndex > items.lastIndex) return emptyMap()
        val moved = items.removeAt(i)
        items.add(newIndex, moved)
        val lower = items.getOrNull(newIndex - 1)?.effectiveRank()
        val upper = items.getOrNull(newIndex + 1)?.effectiveRank()
        return if (lower == null || upper == null || lower < upper) {
            mapOf(moved.id to FractionalIndex.between(lower, upper))
        } else {
            // Neighbour ranks collided; rewrite the whole sibling list to a clean order.
            items.zip(FractionalIndex.rebalance(items.size)).associate { (n, r) -> n.id to r }
        }
    }

    /**
     * Drag-drop reorder: move [id] to just before [beforeId] within its sibling list (the children
     * of [id]'s parent — inbox root or a project). [beforeId] == null drops it at the end. Returns
     * the rank writes (a single fractional index, or a full rebalance on a collision).
     */
    fun reorderBefore(id: Ulid, beforeId: Ulid?, now: Long = Long.MAX_VALUE): Map<Ulid, String> {
        val parent = store.getParent(id)
        val list = inbox(parent, now)
        val moving = list.firstOrNull { it.id.toString() == id.toString() } ?: return emptyMap()
        val siblings = list.filter { it.id.toString() != id.toString() }.toMutableList()
        val idx = when (beforeId) {
            null -> siblings.size
            else -> siblings.indexOfFirst { it.id.toString() == beforeId.toString() }.let { if (it < 0) siblings.size else it }
        }
        siblings.add(idx, moving)
        val lower = siblings.getOrNull(idx - 1)?.effectiveRank()
        val upper = siblings.getOrNull(idx + 1)?.effectiveRank()
        return if (lower == null || upper == null || lower < upper) {
            mapOf(moving.id to FractionalIndex.between(lower, upper))
        } else {
            siblings.zip(FractionalIndex.rebalance(siblings.size)).associate { (n, r) -> n.id to r }
        }
    }

    private fun NodeView.effectiveRank(): String = rank ?: id.toString().lowercase()

    /** The Reference tree (GTD triage §7): live children of the well-known reference root. */
    fun reference(root: Ulid = WellKnownNodes.REFERENCE_ROOT): List<NodeView> = children(root)

    /**
     * Keyword search across all content (GTD triage §7): matches on title/notes/summary
     * via the store's search index. Trashed hits are excluded; FILED items stay findable.
     */
    fun search(query: String, limit: Int = 50): List<NodeView> {
        if (query.isBlank()) return emptyList()
        return store.search(query, limit).mapNotNull { node(it) }.filter { it.status != "DROPPED" }
    }

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
            .filter { it.status != "DONE" && it.status != "DROPPED" && it.status != Status.FILED && (it.deferUntil == null || it.deferUntil <= now) }
            .sortedBy { it.title ?: "" }

    /** Live active tasks waiting on a person (the Waiting tile). */
    fun waitingTasks(now: Long = Long.MAX_VALUE): List<NodeView> =
        activeTasks(now).filter { it.person != null }

    /** Live tasks due at/before [byMillis] (incl. overdue), soonest first — the Today view. */
    fun dueTasks(byMillis: Long): List<NodeView> =
        snapshots()
            .filter { it.kind() == "task" && !it.proposed() }
            .map { it.toView() }
            .filter { it.status != "DONE" && it.status != "DROPPED" && it.status != Status.FILED && it.dueDate != null && it.dueDate <= byMillis }
            .sortedBy { it.dueDate }

    /** Live projects — the move targets for organizing tasks into the tree. */
    fun projects(): List<NodeView> =
        snapshots().filter { it.kind() == "project" && !it.proposed() }
            .map { it.toView() }
            .filter { it.status != "DROPPED" && it.status != Status.FILED }
            .sortedBy { it.title ?: "" }

    /**
     * The context view (launcher spec L4, v0.2 semantics): live, active, non-deferred
     * TASKS across the whole tree tagged with [context] — a flat next-actions list.
     */
    fun contextTasks(context: Ulid, now: Long = Long.MAX_VALUE): List<NodeView> =
        snapshots()
            .filter { it.kind() == "task" && !it.proposed() && it.tags.any { t -> t.toString() == context.toString() } }
            .map { it.toView() }
            .filter { it.status != "DONE" && it.status != "DROPPED" && it.status != Status.FILED && (it.deferUntil == null || it.deferUntil <= now) }
            .sortedBy { it.title ?: "" }

    /** Attachments linked to [node] (payload.nodeId == node), for triage preview (spec §4). */
    fun attachments(node: Ulid): List<AttachmentView> =
        store.project().values
            .filter { it.alive }
            .mapNotNull { snap ->
                val payload = snap.fields[dev.njr.zync.core.merge.ATTACHMENT_FIELD] as? JsonObject ?: return@mapNotNull null
                if ((payload["nodeId"] as? JsonPrimitive)?.content != node.toString()) return@mapNotNull null
                AttachmentView(
                    id = snap.entityId,
                    type = (payload["type"] as? JsonPrimitive)?.content,
                    filename = (payload["relativePath"] as? JsonPrimitive)?.content,
                    blobHash = (payload["blobHash"] as? JsonPrimitive)?.content,
                )
            }
            .sortedBy { it.id.toString() }

    /** 1-based depth via the O(1) parent index (no projection fold): root-parented = 1. */
    fun depthOf(id: Ulid, max: Int = 8): Int {
        var depth = 1 // the node itself sits at level 1 when root-parented
        var current = store.getParent(id)
        val seen = HashSet<String>()
        while (current != null && depth <= max + 2 && seen.add(current.toString())) {
            depth++
            current = store.getParent(current)
        }
        return depth
    }

    /** Tallest descendant chain below [id]; a leaf = 0. Bounded to guard corrupt cycles. */
    fun subtreeHeight(id: Ulid, budget: Int = 12): Int =
        heightIn(childrenIndex(store.project()), id, budget)

    private fun childrenIndex(snaps: Map<Ulid, EntitySnapshot>): Map<String, List<Ulid>> =
        snaps.values
            .filter {
                it.alive && it.kind() != "context" && it.kind() != "comment" &&
                    it.kind() !in AgentFlow.INTERNAL_KINDS && !it.proposed()
            }
            .groupBy({ it.parent?.toString() ?: "" }, { it.entityId })

    private fun heightIn(index: Map<String, List<Ulid>>, id: Ulid, budget: Int): Int {
        if (budget <= 0) return 0
        val kids = index[id.toString()].orEmpty()
        if (kids.isEmpty()) return 0
        return 1 + kids.maxOf { heightIn(index, it, budget - 1) }
    }

    /** True iff moving [node] under [newParent] would push any node past [max] levels (spec §8, Q8). */
    fun moveWouldExceedDepth(node: Ulid, newParent: Ulid, max: Int = 4): Boolean =
        depthOf(newParent) + 1 + heightIn(childrenIndex(store.project()), node, 12) > max

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
        linkTitle = fields[Fields.LINK_TITLE].asString(),
        linkPreview = fields[Fields.LINK_PREVIEW].asString(),
        linkUrl = fields[Fields.LINK_URL].asString(),
        freeTags = fields.entries
            .filter { it.key.startsWith(Fields.FREE_TAG_PREFIX) && (it.value as? JsonPrimitive)?.content == "true" }
            .map { it.key.removePrefix(Fields.FREE_TAG_PREFIX) }
            .sorted(),
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

    private fun JsonElement?.asString(): String? = stringContent()
}
