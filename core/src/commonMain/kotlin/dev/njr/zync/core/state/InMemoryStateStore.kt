package dev.njr.zync.core.state

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.content.FtsQuery
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Op
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * In-memory [StateStore] — the reference implementation and test double. The
 * SQLDelight-backed store (M4/M5) must behave identically; the conformance and
 * convergence tests pin that behavior against this one.
 */
class InMemoryStateStore : StateStore {
    private val applied = mutableSetOf<Ulid>()
    private val registers = mutableMapOf<RegisterKey, RegisterValue>()
    private val tombstones = mutableMapOf<Ulid, Hlc>()
    private val tags = mutableMapOf<TagKey, TagValue>()
    private val moves = mutableMapOf<Ulid, Op.Move>() // keyed by opId (idempotent insert)
    private val parents = mutableMapOf<Ulid, Ulid>()

    override fun isApplied(opId: Ulid): Boolean = opId in applied
    override fun markApplied(opId: Ulid) { applied += opId }

    override fun getRegister(key: RegisterKey): RegisterValue? = registers[key]
    override fun putRegister(key: RegisterKey, value: RegisterValue) { registers[key] = value }

    override fun getTombstone(entityId: Ulid): Hlc? = tombstones[entityId]
    override fun putTombstone(entityId: Ulid, hlc: Hlc) { tombstones[entityId] = hlc }

    override fun getTag(key: TagKey): TagValue? = tags[key]
    override fun putTag(key: TagKey, value: TagValue) { tags[key] = value }

    override fun putMove(move: Op.Move) { moves[move.opId] = move }
    override fun moveLog(): List<Op.Move> = moves.values.toList()
    override fun setParent(nodeId: Ulid, parentId: Ulid?) {
        if (parentId == null) parents.remove(nodeId) else parents[nodeId] = parentId
    }
    override fun getParent(nodeId: Ulid): Ulid? = parents[nodeId]

    override fun allRegisters(): Map<RegisterKey, RegisterValue> = registers.toMap()
    override fun allTombstones(): Map<Ulid, Hlc> = tombstones.toMap()
    override fun allTags(): Map<TagKey, TagValue> = tags.toMap()
    override fun allParents(): Map<Ulid, Ulid> = parents.toMap()

    override fun search(query: String, limit: Int): List<Ulid> {
        val tokens = FtsQuery.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        return project().values.asSequence()
            .filter { it.alive && text(it.fields["kind"]) in SEARCHABLE_KINDS && text(it.fields["status"]) != "DROPPED" }
            .filter { snap ->
                // Substring AND-match over the joined body — mirrors the durable store's LIKE.
                val body = listOf("title", "notes", "summary")
                    .mapNotNull { text(snap.fields[it]) }
                    .joinToString(" ").lowercase()
                tokens.all { body.contains(it) }
            }
            .map { it.entityId }
            .take(limit)
            .toList()
    }

    private fun text(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private companion object {
        val SEARCHABLE_KINDS = setOf("task", "project", "attachment")
    }
}
