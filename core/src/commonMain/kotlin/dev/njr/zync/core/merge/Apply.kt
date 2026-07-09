package dev.njr.zync.core.merge

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.state.RegisterValue
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.core.state.TagKey
import dev.njr.zync.core.state.TagValue

/** Register field under which an [Op.AddAttachment] immutable payload is stored. */
const val ATTACHMENT_FIELD: String = "@attachment"

/**
 * Apply one op to [store], deterministically (spec §5). Idempotent (dedupe by
 * `opId`) and commutative for the register family, so delivery order never changes
 * the final state:
 * - SetField / AddAttachment: LWW — write iff no register or strictly higher HLC.
 * - Tombstone: keep the max HLC; tombstone wins over concurrent edits at projection.
 * - AddTag / RemoveTag: LWW-boolean `present` on `(node, context)`.
 * - Move: appended to the HLC-ordered move log, then the parent projection is
 *   reintegrated with cycle-skip ([reintegrateMoves]).
 */
fun apply(op: Op, store: StateStore) {
    if (store.isApplied(op.opId)) return
    when (op) {
        is Op.SetField -> lww(store, RegisterKey(op.entityId, op.field), RegisterValue(op.value, op.hlc, op.actor))
        is Op.AddAttachment -> lww(store, RegisterKey(op.entityId, ATTACHMENT_FIELD), RegisterValue(op.value, op.hlc, op.actor))
        is Op.Tombstone -> {
            val existing = store.getTombstone(op.entityId)
            if (existing == null || op.hlc > existing) store.putTombstone(op.entityId, op.hlc)
        }
        is Op.AddTag -> applyTag(store, TagKey(op.entityId, op.contextId), present = true, op)
        is Op.RemoveTag -> applyTag(store, TagKey(op.entityId, op.contextId), present = false, op)
        is Op.Move -> {
            store.putMove(op)
            reintegrateMoves(store)
        }
    }
    store.markApplied(op.opId)
}

private fun lww(store: StateStore, key: RegisterKey, incoming: RegisterValue) {
    val existing = store.getRegister(key)
    if (existing == null || incoming.hlc > existing.hlc) store.putRegister(key, incoming)
}

private fun applyTag(store: StateStore, key: TagKey, present: Boolean, op: Op) {
    val existing = store.getTag(key)
    if (existing == null || op.hlc > existing.hlc) store.putTag(key, TagValue(present, op.hlc))
}

/**
 * Recompute every node's parent from the full move log in HLC order (Kleppmann,
 * spec §4). Applying a move whose target is the node itself or a descendant of the
 * node would form a cycle, so it is **skipped**. Full replay over the sorted log is
 * order-independent by construction and inherently absorbs late-arriving moves
 * (they re-sort into place), so all replicas converge to one parent map.
 */
fun reintegrateMoves(store: StateStore) {
    val ordered = store.moveLog().sortedBy { it.hlc }
    val parent = mutableMapOf<Ulid, Ulid>()
    for (move in ordered) {
        val node = move.entityId
        val newParent = move.newParentId
        if (!wouldCycle(node, newParent, parent)) parent[node] = newParent
    }
    // Reconcile the store's projected parents with the freshly integrated map.
    for (node in store.allParents().keys - parent.keys) store.setParent(node, null)
    for ((node, p) in parent) store.setParent(node, p)
}

/** True if setting `node.parent = newParent` would create a cycle (newParent is node or its descendant). */
private fun wouldCycle(node: Ulid, newParent: Ulid, parent: Map<Ulid, Ulid>): Boolean {
    var cursor: Ulid? = newParent
    while (cursor != null) {
        if (cursor == node) return true
        cursor = parent[cursor]
    }
    return false
}

/**
 * Fold the store into a per-entity [EntitySnapshot] map — the queryable projection
 * used by tests and callers. Rebuildable from the log at any time.
 */
fun StateStore.project(): Map<Ulid, EntitySnapshot> {
    val registers = allRegisters()
    val tombstones = allTombstones()
    val tags = allTags()
    val parents = allParents()

    val entityIds = buildSet {
        registers.keys.forEach { add(it.entityId) }
        addAll(tombstones.keys)
        tags.keys.forEach { add(it.nodeId) }
        addAll(parents.keys)
    }

    return entityIds.associateWith { id ->
        val fields = registers
            .filterKeys { it.entityId == id }
            .entries
            .associate { (key, value) -> key.field to value.value }
        val entityTags = tags
            .filter { (key, value) -> key.nodeId == id && value.present }
            .keys
            .map { it.contextId }
            .toSet()
        EntitySnapshot(
            entityId = id,
            alive = fields.isNotEmpty() && id !in tombstones,
            fields = fields,
            tags = entityTags,
            parent = parents[id],
        )
    }
}
