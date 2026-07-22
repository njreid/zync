package dev.njr.zync.data

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.state.RegisterValue
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.core.state.TagKey
import dev.njr.zync.core.state.TagValue
import dev.njr.zync.core.content.FtsQuery
import dev.njr.zync.core.content.stringContent
import dev.njr.zync.data.db.ZyncDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * SQLDelight-backed [StateStore] — the durable implementation of `core`'s port,
 * shared by the server (JVM driver) and, later, the phone (Android driver). Behaves
 * identically to `core`'s InMemoryStateStore; the parity tests pin that.
 *
 * `value`/`actor` are persisted as JSON text via [json]; HLCs as their three
 * columns. Callers wrap multi-op ingest in a DB transaction for atomicity (the
 * server does; `apply` itself is unaware of transactions).
 */
class SqlDelightStateStore(
    private val db: ZyncDatabase,
    private val json: Json = Json,
) : StateStore {

    init {
        // One-time backfill for DBs migrated to v6 with an empty search index (spec §7 §2d):
        // if the index is empty but content exists, rebuild it from the register table.
        // (One-time post-migration cost; runs on the constructing thread.)
        if (db.searchIndexQueries.countDocs().executeAsOne() == 0L) {
            val ids = db.searchIndexQueries.allDocEntityIds().executeAsList()
            if (ids.isNotEmpty()) {
                ids.forEach { reindex(it) }
                // If nothing was indexable (e.g. contexts/comments only), write a sentinel
                // so countDocs stays > 0 and the scan doesn't re-run on every construction.
                if (db.searchIndexQueries.countDocs().executeAsOne() == 0L) {
                    db.searchIndexQueries.insertDoc(SENTINEL_ID, "")
                }
            }
        }
    }

    override fun isApplied(opId: Ulid): Boolean =
        db.appliedOpQueries.isApplied(opId.toString()).executeAsOne() > 0L

    override fun markApplied(opId: Ulid) {
        db.appliedOpQueries.markApplied(opId.toString())
    }

    override fun getRegister(key: RegisterKey): RegisterValue? =
        db.registerQueries.getRegister(key.entityId.toString(), key.field).executeAsOneOrNull()?.let {
            RegisterValue(
                value = json.decodeFromString(JsonElement.serializer(), it.value_),
                hlc = Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device),
                actor = json.decodeFromString(Actor.serializer(), it.actor),
            )
        }

    override fun putRegister(key: RegisterKey, value: RegisterValue) {
        db.registerQueries.putRegister(
            entity_id = key.entityId.toString(),
            field_ = key.field,
            value_ = json.encodeToString(JsonElement.serializer(), value.value),
            hlc_physical = value.hlc.physical,
            hlc_counter = value.hlc.counter.toLong(),
            hlc_device = value.hlc.deviceId,
            actor = json.encodeToString(Actor.serializer(), value.actor),
        )
        if (key.field in REINDEX_TRIGGERS) reindex(key.entityId.toString())
    }

    override fun getTombstone(entityId: Ulid): Hlc? =
        db.tombstoneQueries.getTombstone(entityId.toString()).executeAsOneOrNull()?.let {
            Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device)
        }

    override fun putTombstone(entityId: Ulid, hlc: Hlc) {
        db.tombstoneQueries.putTombstone(entityId.toString(), hlc.physical, hlc.counter.toLong(), hlc.deviceId)
        reindex(entityId.toString()) // now dead → drop from the search index
    }

    override fun getTag(key: TagKey): TagValue? =
        db.tagQueries.getTag(key.nodeId.toString(), key.contextId.toString()).executeAsOneOrNull()?.let {
            TagValue(present = it.present != 0L, hlc = Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device))
        }

    override fun putTag(key: TagKey, value: TagValue) {
        db.tagQueries.putTag(
            node_id = key.nodeId.toString(),
            context_id = key.contextId.toString(),
            present = if (value.present) 1L else 0L,
            hlc_physical = value.hlc.physical,
            hlc_counter = value.hlc.counter.toLong(),
            hlc_device = value.hlc.deviceId,
        )
    }

    override fun putMove(move: Op.Move) {
        db.moveLogQueries.putMove(
            op_id = move.opId.toString(),
            node_id = move.entityId.toString(),
            new_parent_id = move.newParentId.toString(),
            hlc_physical = move.hlc.physical,
            hlc_counter = move.hlc.counter.toLong(),
            hlc_device = move.hlc.deviceId,
            actor = json.encodeToString(Actor.serializer(), move.actor),
            device_id = move.deviceId,
            wall_clock = move.wallClock,
        )
    }

    override fun moveLog(): List<Op.Move> =
        db.moveLogQueries.allMoves().executeAsList().map {
            Op.Move(
                opId = Ulid.parse(it.op_id),
                entityId = Ulid.parse(it.node_id),
                entityType = EntityType.Node,
                hlc = Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device),
                actor = json.decodeFromString(Actor.serializer(), it.actor),
                deviceId = it.device_id,
                wallClock = it.wall_clock,
                newParentId = Ulid.parse(it.new_parent_id),
            )
        }

    override fun setParent(nodeId: Ulid, parentId: Ulid?) {
        if (parentId == null) db.moveParentQueries.deleteParent(nodeId.toString())
        else db.moveParentQueries.setParent(nodeId.toString(), parentId.toString())
    }

    override fun getParent(nodeId: Ulid): Ulid? =
        db.moveParentQueries.getParent(nodeId.toString()).executeAsOneOrNull()?.let { Ulid.parse(it) }

    override fun allRegisters(): Map<RegisterKey, RegisterValue> =
        db.registerQueries.allRegisters().executeAsList().associate {
            RegisterKey(Ulid.parse(it.entity_id), it.field_) to RegisterValue(
                value = json.decodeFromString(JsonElement.serializer(), it.value_),
                hlc = Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device),
                actor = json.decodeFromString(Actor.serializer(), it.actor),
            )
        }

    override fun allTombstones(): Map<Ulid, Hlc> =
        db.tombstoneQueries.allTombstones().executeAsList().associate {
            Ulid.parse(it.entity_id) to Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device)
        }

    override fun allTags(): Map<TagKey, TagValue> =
        db.tagQueries.allTags().executeAsList().associate {
            TagKey(Ulid.parse(it.node_id), Ulid.parse(it.context_id)) to
                TagValue(present = it.present != 0L, hlc = Hlc(it.hlc_physical, it.hlc_counter.toInt(), it.hlc_device))
        }

    override fun allParents(): Map<Ulid, Ulid> =
        db.moveParentQueries.allParents().executeAsList().associate {
            Ulid.parse(it.node_id) to Ulid.parse(it.parent_id)
        }

    override fun search(query: String, limit: Int): List<Ulid> {
        val tokens = FtsQuery.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        // Candidate rows via the most selective (longest) token; AND the rest in Kotlin.
        val pivot = tokens.maxByOrNull { it.length }!!
        return db.searchIndexQueries.searchByToken(pivot).executeAsList().asSequence()
            .filter { row -> tokens.all { it in row.body } }
            .map { Ulid.parse(it.entity_id) }
            .take(limit)
            .toList()
    }

    /**
     * Rebuild one entity's search row from its registers (spec §7 D1): DELETE then, if the
     * entity is alive and a searchable kind with text, INSERT its lowercased body. Runs
     * inside the caller's ingest transaction so the index commits atomically with the write.
     */
    private fun reindex(id: String) {
        db.searchIndexQueries.deleteDoc(id)
        if (db.tombstoneQueries.getTombstone(id).executeAsOneOrNull() != null) return
        val fields = db.searchIndexQueries.docFields(id).executeAsList()
            .associate { it.field_name to json.decodeFromString(JsonElement.serializer(), it.field_value).stringContent() }
        if (fields["kind"] !in SEARCHABLE_KINDS) return
        if (fields["status"] == "DROPPED") return // trashed items drop out of search
        val body = listOf(fields["title"], fields["notes"], fields["summary"])
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .lowercase()
        if (body.isBlank()) return
        db.searchIndexQueries.insertDoc(id, body)
    }

    private companion object {
        val SEARCHABLE_KINDS = setOf("task", "project", "attachment")
        val REINDEX_TRIGGERS = setOf("kind", "title", "notes", "summary", "status")

        /** Reserved non-ULID key so a content-only-but-unindexable DB doesn't re-scan each open. */
        const val SENTINEL_ID = "search-index-sentinel"
    }
}
