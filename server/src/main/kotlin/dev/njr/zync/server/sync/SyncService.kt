package dev.njr.zync.server.sync

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.sync.BootstrapSnapshot
import dev.njr.zync.core.sync.PullResponse
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.core.sync.PushResponse
import dev.njr.zync.core.sync.RegisterEntry
import dev.njr.zync.core.sync.TagEntry
import dev.njr.zync.core.sync.TombstoneEntry
import dev.njr.zync.data.SqlDelightStateStore
import dev.njr.zync.data.db.ZyncDatabase
import kotlinx.serialization.json.Json

/** Return a copy of this op with the transport [seq] assigned. */
fun Op.withSeq(seq: Long): Op = when (this) {
    is Op.SetField -> copy(seq = seq)
    is Op.Move -> copy(seq = seq)
    is Op.AddTag -> copy(seq = seq)
    is Op.RemoveTag -> copy(seq = seq)
    is Op.AddAttachment -> copy(seq = seq)
    is Op.Tombstone -> copy(seq = seq)
}

/**
 * The integration point: ingests ops (idempotent by opId), assigns transport `seq`,
 * persists to op_log, and merges into the SQLDelight-backed StateStore via `core`.
 * Push/pull/bootstrap are the sync protocol (spec §6). Ingest is transactional so an
 * op's op_log row, its seq, and its merged state land atomically.
 */
class SyncService(
    private val db: ZyncDatabase,
    private val json: Json = Json,
) {
    private val store = SqlDelightStateStore(db, json)
    private val seq = SeqAllocator(initialHead = db.transportQueries.headSeq().executeAsOne())

    /** Ingest pending ops; dedupe by opId; ack everything the server now holds. */
    fun push(request: PushRequest): PushResponse {
        db.transaction {
            for (op in request.ops) {
                if (db.transportQueries.opExists(op.opId.toString()).executeAsOne() > 0L) continue
                val assigned = op.withSeq(seq.next())
                insertOpLog(assigned)
                apply(assigned, store)
            }
        }
        return PushResponse(ackedOpIds = request.ops.map { it.opId }, serverHead = seq.head())
    }

    /** Ops with `seq > since`, ordered and paged; plus the current head. */
    fun pull(since: Long, limit: Long = DEFAULT_PAGE): PullResponse {
        val ops = db.transportQueries.selectSince(since, limit).executeAsList()
            .map { json.decodeFromString(Op.serializer(), it) }
        return PullResponse(ops = ops, head = seq.head())
    }

    /** Current projected state (for the debug UI). */
    fun state() = store.project()

    /** The most recent ops by seq (for the debug UI). */
    fun recentOps(limit: Long = 50): List<Op> =
        db.transportQueries.selectRecent(limit).executeAsList().map { json.decodeFromString(Op.serializer(), it) }

    /** Compacted snapshot for a fresh install / new device. */
    fun bootstrap(): BootstrapSnapshot = BootstrapSnapshot(
        registers = store.allRegisters().map { (key, value) ->
            RegisterEntry(key.entityId, key.field, value.value, value.hlc, value.actor)
        },
        tombstones = store.allTombstones().map { (entityId, hlc) -> TombstoneEntry(entityId, hlc) },
        tags = store.allTags().map { (key, value) -> TagEntry(key.nodeId, key.contextId, value.present, value.hlc) },
        moves = store.moveLog(),
        headSeq = seq.head(),
    )

    private fun insertOpLog(op: Op) {
        db.transportQueries.insertOp(
            op_id = op.opId.toString(),
            seq = op.seq,
            entity_id = op.entityId.toString(),
            entity_type = op.entityType.name,
            op_type = op::class.simpleName ?: "Op",
            payload = json.encodeToString(Op.serializer(), op),
            hlc_physical = op.hlc.physical,
            hlc_counter = op.hlc.counter.toLong(),
            hlc_device = op.hlc.deviceId,
            device_id = op.deviceId,
            wall_clock = op.wallClock,
            synced = 1,
        )
    }

    companion object {
        const val DEFAULT_PAGE: Long = 500
    }
}
