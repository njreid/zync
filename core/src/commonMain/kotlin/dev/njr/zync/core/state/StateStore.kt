package dev.njr.zync.core.state

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Op

/**
 * Storage port the merge logic runs against — abstract so `core` stays DB- and
 * platform-agnostic. Tests use [InMemoryStateStore]; the SQLDelight impl arrives
 * in `data` (M4/M5). `apply` (see `merge/Apply.kt`) never touches SQL directly.
 *
 * Holds the LWW register map, tombstones, tag-membership registers, the move log
 * + its integrated parent projection, and the set of applied op ids (idempotency).
 */
interface StateStore {
    // --- idempotency (dedupe by opId) ---
    fun isApplied(opId: Ulid): Boolean
    fun markApplied(opId: Ulid)

    // --- LWW registers ---
    fun getRegister(key: RegisterKey): RegisterValue?
    fun putRegister(key: RegisterKey, value: RegisterValue)

    // --- tombstones ---
    fun getTombstone(entityId: Ulid): Hlc?
    fun putTombstone(entityId: Ulid, hlc: Hlc)

    // --- tag membership ---
    fun getTag(key: TagKey): TagValue?
    fun putTag(key: TagKey, value: TagValue)

    // --- move log + integrated parent projection ---
    fun putMove(move: Op.Move)
    fun moveLog(): List<Op.Move>
    fun setParent(nodeId: Ulid, parentId: Ulid?)
    fun getParent(nodeId: Ulid): Ulid?

    // --- enumeration (for project()) ---
    fun allRegisters(): Map<RegisterKey, RegisterValue>
    fun allTombstones(): Map<Ulid, Hlc>
    fun allTags(): Map<TagKey, TagValue>
    fun allParents(): Map<Ulid, Ulid>
}
