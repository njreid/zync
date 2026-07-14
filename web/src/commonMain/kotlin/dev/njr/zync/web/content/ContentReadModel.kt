package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

/** A node as the shared UI reads it — folded from the op-log projection. */
data class NodeView(
    val id: Ulid,
    val kind: String?,
    val title: String?,
    val notes: String?,
    val status: String?,
    val deferUntil: Long?,
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

    private fun EntitySnapshot.kind(): String? = fields["kind"].asString()

    private fun EntitySnapshot.proposed(): Boolean =
        (fields[AgentFlow.FIELD_PROPOSED] as? JsonPrimitive)?.content == "true"

    private fun EntitySnapshot.toView() = NodeView(
        id = entityId,
        kind = fields["kind"].asString(),
        title = fields["title"].asString(),
        notes = fields["notes"].asString(),
        status = fields["status"].asString(),
        deferUntil = (fields["deferUntil"] as? JsonPrimitive)?.content?.toLongOrNull(),
        parent = parent,
        tags = tags,
        alive = alive,
        proposed = proposed(),
    )

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content
}
