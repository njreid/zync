package dev.njr.zync.core.merge

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.state.StateStore

/**
 * Kleppmann tree-move integration (spec §4) — the one non-trivially-commutative
 * part of the merge. `Move(node, newParent)` can form cycles (A→under-B while
 * B→under-A) or orphan subtrees; this resolves them deterministically so every
 * replica converges on one parent map.
 *
 * Strategy: **full replay** of the entire move log in HLC order. When applying a
 * move whose `newParent` is the node itself or a descendant of the node, applying
 * it would create a cycle, so it is **skipped** (the node stays put). Re-running
 * over the sorted log on every new move is what makes it order-independent and
 * absorbs late-arriving (lower-HLC) moves — they simply re-sort into place and the
 * result is recomputed. This is O(#moves) per integration, fine at personal scale;
 * the spec's incremental undo/redo is an equivalent optimization we can adopt later
 * if the move log grows large.
 *
 * Result invariants: no cycles, no orphans — following any node's parent chain
 * terminates at a root (null parent).
 */
fun reintegrateMoves(store: StateStore) {
    val ordered = store.moveLog().sortedBy { it.hlc }
    val parent = mutableMapOf<Ulid, Ulid>()
    for (move in ordered) {
        if (!wouldCycle(move.entityId, move.newParentId, parent)) {
            parent[move.entityId] = move.newParentId
        }
    }
    // Reconcile the store's projected parents with the freshly integrated map:
    // clear any node that no longer has a parent, then write the integrated ones.
    for (node in store.allParents().keys - parent.keys) store.setParent(node, null)
    for ((node, p) in parent) store.setParent(node, p)
}

/**
 * True if setting `node.parent = newParent` would create a cycle — i.e. `newParent`
 * is `node` itself or a descendant of `node` (walking ancestors of `newParent`
 * reaches `node`).
 */
private fun wouldCycle(node: Ulid, newParent: Ulid, parent: Map<Ulid, Ulid>): Boolean {
    var cursor: Ulid? = newParent
    while (cursor != null) {
        if (cursor == node) return true
        cursor = parent[cursor]
    }
    return false
}
