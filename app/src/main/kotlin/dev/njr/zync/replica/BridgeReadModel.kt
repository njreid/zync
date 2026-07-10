package dev.njr.zync.replica

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A node as the bridge UI reads it — folded from the op-log projection, not Room. */
@Serializable
data class NodeView(
    val id: String,
    val title: String?,
    val notes: String?,
    val status: String?,
    val parent: String?,
    val tags: List<String>,
    val alive: Boolean,
)

/**
 * M5 bridge: the phone's existing web UI reads through this instead of Room, so the
 * phone stays usable while the mutation path moves to the op log. Every view is a fold
 * of the `core` projection over the `:data` store; the real shared web module is M6.
 */
class BridgeReadModel(private val store: StateStore) {
    /** Live children of [parent] (null = root), title-sorted. */
    fun children(parent: Ulid?): List<NodeView> =
        store.project().values
            .filter { it.alive && it.parent?.toString() == parent?.toString() }
            .map { it.toView() }
            .sortedBy { it.title ?: "" }

    /** Inbox: live children of [inbox] that aren't completed/dropped. */
    fun inbox(inbox: Ulid?): List<NodeView> =
        children(inbox).filter { it.status != "DONE" && it.status != "DROPPED" }

    fun node(id: Ulid): NodeView? =
        store.project()[id]?.takeIf { it.alive }?.toView()

    fun all(): List<NodeView> = store.project().values.filter { it.alive }.map { it.toView() }

    private fun EntitySnapshot.toView() = NodeView(
        id = entityId.toString(),
        title = fields["title"].asString(),
        notes = fields["notes"].asString(),
        status = fields["status"].asString(),
        parent = parent?.toString(),
        tags = tags.map { it.toString() },
        alive = alive,
    )

    private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.content
}
