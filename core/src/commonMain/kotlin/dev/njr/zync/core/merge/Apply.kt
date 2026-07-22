package dev.njr.zync.core.merge

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
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
    val existing = store.getRegister(key) ?: run { store.putRegister(key, incoming); return }
    // A Bot value is "advisory": it never beats an authoritative (non-Bot: Human/Operator/Agent)
    // value, and any authoritative value supersedes a Bot value, regardless of HLC — so a bot can
    // propose but never clobber a human decision (external-op-api spec §1). This is a TOTAL order
    // (authoritative outranks advisory; ties broken by HLC), so the merge stays a proper join and
    // converges regardless of apply order even when a third actor sits between the two by HLC.
    // A pairwise Human-vs-Bot-only rule would NOT converge in that three-way case.
    val wins = when {
        existing.actor is Actor.Bot && incoming.actor !is Actor.Bot -> true
        existing.actor !is Actor.Bot && incoming.actor is Actor.Bot -> false
        else -> incoming.hlc > existing.hlc
    }
    if (wins) store.putRegister(key, incoming)
}

private fun applyTag(store: StateStore, key: TagKey, present: Boolean, op: Op) {
    val existing = store.getTag(key)
    if (existing == null || op.hlc > existing.hlc) store.putTag(key, TagValue(present, op.hlc))
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
