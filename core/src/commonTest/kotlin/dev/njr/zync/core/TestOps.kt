package dev.njr.zync.core

import dev.njr.zync.core.clock.FixedClock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/** Deterministic ULID for a given seed — stable entity ids across a test. */
fun id(seed: Int): Ulid = Ulid.generate(FixedClock(seed.toLong()), Random(seed.toLong()))

fun hlc(ms: Long, ctr: Int = 0, dev: String = "dev"): Hlc = Hlc(ms, ctr, dev)

fun str(value: String): JsonElement = JsonPrimitive(value)

/**
 * Builds ops with auto-minted unique opIds so tests can focus on entity/field/hlc.
 * The opId is irrelevant to merge outcome except as the idempotency key.
 */
class OpFactory(private val device: String = "dev") {
    private var counter = 100_000
    private fun nextOpId(): Ulid {
        val n = counter++
        return Ulid.generate(FixedClock(n.toLong()), Random(n.toLong()))
    }

    fun setField(entity: Ulid, field: String, value: JsonElement, hlc: Hlc, actor: Actor = Actor.Human, type: EntityType = EntityType.Node) =
        Op.SetField(nextOpId(), entity, type, hlc, actor, device, hlc.physical, field, value)

    fun tombstone(entity: Ulid, hlc: Hlc, actor: Actor = Actor.Human, type: EntityType = EntityType.Node) =
        Op.Tombstone(nextOpId(), entity, type, hlc, actor, device, hlc.physical)

    fun addTag(node: Ulid, context: Ulid, hlc: Hlc, actor: Actor = Actor.Human) =
        Op.AddTag(nextOpId(), node, EntityType.Tag, hlc, actor, device, hlc.physical, context)

    fun removeTag(node: Ulid, context: Ulid, hlc: Hlc, actor: Actor = Actor.Human) =
        Op.RemoveTag(nextOpId(), node, EntityType.Tag, hlc, actor, device, hlc.physical, context)

    fun addAttachment(attachment: Ulid, value: JsonElement, hlc: Hlc, actor: Actor = Actor.Human) =
        Op.AddAttachment(nextOpId(), attachment, EntityType.Attachment, hlc, actor, device, hlc.physical, value)

    fun move(node: Ulid, newParent: Ulid, hlc: Hlc, actor: Actor = Actor.Human) =
        Op.Move(nextOpId(), node, EntityType.Node, hlc, actor, device, hlc.physical, newParent)
}
