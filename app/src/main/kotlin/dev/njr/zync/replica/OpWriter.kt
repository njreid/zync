package dev.njr.zync.replica

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.data.db.ZyncDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random

/**
 * The single entry point for phone-side domain mutations (spec §0: the op log is the
 * source of truth). Every mutation is written as an op that is (1) applied to the local
 * [StateStore] and (2) appended to op_log as unsynced — atomically — so it survives
 * offline and is pushed on reconnect. HLCs come from the persisted [LocalHlc].
 */
class OpWriter(
    private val db: ZyncDatabase,
    private val store: StateStore,
    private val hlc: LocalHlc,
    private val deviceId: String,
    private val clock: Clock,
    private val random: Random,
    private val actor: Actor = Actor.Human,
    private val json: Json = Json,
) {
    /** Create a node (optionally under [parent]); returns its new id. */
    fun createNode(title: String, parent: Ulid? = null): Ulid {
        val id = newId()
        setField(id, "title", JsonPrimitive(title))
        if (parent != null) move(id, parent)
        return id
    }

    fun setField(entity: Ulid, field: String, value: JsonElement): Op {
        return record(Op.SetField(newId(), entity, EntityType.Node, hlc.now(), actor, deviceId, clock.nowMillis(), field, value))
    }

    fun move(node: Ulid, newParent: Ulid): Op =
        record(Op.Move(newId(), node, EntityType.Node, hlc.now(), actor, deviceId, clock.nowMillis(), newParent))

    fun addTag(node: Ulid, context: Ulid): Op =
        record(Op.AddTag(newId(), node, EntityType.Tag, hlc.now(), actor, deviceId, clock.nowMillis(), context))

    fun removeTag(node: Ulid, context: Ulid): Op =
        record(Op.RemoveTag(newId(), node, EntityType.Tag, hlc.now(), actor, deviceId, clock.nowMillis(), context))

    fun addAttachment(attachment: Ulid, payload: JsonElement): Op =
        record(Op.AddAttachment(newId(), attachment, EntityType.Attachment, hlc.now(), actor, deviceId, clock.nowMillis(), payload))

    /** Mint an attachment entity linked to [node] (immutable payload); returns its id. */
    fun createAttachment(node: Ulid, type: String, blobHash: String, relativePath: String): Ulid {
        val id = newId()
        addAttachment(
            id,
            buildJsonObject {
                put("nodeId", JsonPrimitive(node.toString()))
                put("type", JsonPrimitive(type))
                put("blobHash", JsonPrimitive(blobHash))
                put("relativePath", JsonPrimitive(relativePath))
            },
        )
        return id
    }

    fun tombstone(entity: Ulid, type: EntityType = EntityType.Node): Op =
        record(Op.Tombstone(newId(), entity, type, hlc.now(), actor, deviceId, clock.nowMillis()))

    private fun newId(): Ulid = Ulid.generate(clock, random)

    private fun record(op: Op): Op {
        db.transaction {
            apply(op, store)
            db.transportQueries.insertOp(
                op_id = op.opId.toString(),
                seq = null, // assigned by the server on ingest
                entity_id = op.entityId.toString(),
                entity_type = op.entityType.name,
                op_type = op::class.simpleName ?: "Op",
                payload = json.encodeToString(Op.serializer(), op),
                hlc_physical = op.hlc.physical,
                hlc_counter = op.hlc.counter.toLong(),
                hlc_device = op.hlc.deviceId,
                device_id = op.deviceId,
                wall_clock = op.wallClock,
                synced = 0,
            )
        }
        return op
    }
}
