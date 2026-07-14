package dev.njr.zync.server

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

private class FixedClock(private val millis: Long) : Clock {
    override fun nowMillis(): Long = millis
}

fun id(seed: Int): Ulid = Ulid.generate(FixedClock(seed.toLong()), Random(seed.toLong()))

fun hlc(ms: Long, ctr: Int = 0, dev: String = "phone") = Hlc(ms, ctr, dev)

fun str(value: String): JsonElement = JsonPrimitive(value)

/** Minimal op builder with auto-minted unique opIds for server tests. */
class Ops(private val device: String = "phone") {
    private var counter = 800_000
    private fun nextId(): Ulid {
        val n = counter++
        return Ulid.generate(FixedClock(n.toLong()), Random(n.toLong()))
    }

    fun setField(entity: Ulid, field: String, value: JsonElement, hlc: Hlc, actor: Actor = Actor.Human) =
        Op.SetField(nextId(), entity, EntityType.Node, hlc, actor, device, hlc.physical, field, value)

    fun tombstone(entity: Ulid, hlc: Hlc) =
        Op.Tombstone(nextId(), entity, EntityType.Node, hlc, Actor.Human, device, hlc.physical)

    fun move(node: Ulid, parent: Ulid, hlc: Hlc) =
        Op.Move(nextId(), node, EntityType.Node, hlc, Actor.Human, device, hlc.physical, parent)

    fun addTag(node: Ulid, context: Ulid, hlc: Hlc) =
        Op.AddTag(nextId(), node, EntityType.Tag, hlc, Actor.Human, device, hlc.physical, context)

    fun removeTag(node: Ulid, context: Ulid, hlc: Hlc) =
        Op.RemoveTag(nextId(), node, EntityType.Tag, hlc, Actor.Human, device, hlc.physical, context)
}

/** Diverse random batch with globally-unique HLCs (mirrors the core generator). */
class RandomOps(seed: Int) {
    private val rng = Random(seed)
    private val entities = (1..5).map { id(it) }
    private val contexts = (20..22).map { id(it) }
    private val used = mutableSetOf<Hlc>()
    private var counter = 900_000

    private fun nextId(): Ulid {
        val n = counter++
        return Ulid.generate(FixedClock(n.toLong()), Random(n.toLong()))
    }

    private fun uniqueHlc(): Hlc {
        // Space = 7 physicals × 3 counters × 3 devices = 63 unique HLCs; bail instead
        // of spinning forever when a batch asks for more.
        repeat(10_000) {
            val h = Hlc(rng.nextLong(1, 8), rng.nextInt(0, 3), listOf("A", "B", "C")[rng.nextInt(3)])
            if (used.add(h)) return h
        }
        error("RandomOps HLC space exhausted (63 unique max) — request fewer ops per instance")
    }

    fun batch(count: Int): List<Op> = List(count) {
        val entity = entities[rng.nextInt(entities.size)]
        val h = uniqueHlc()
        when (rng.nextInt(6)) {
            0 -> Op.SetField(nextId(), entity, EntityType.Node, h, Actor.Human, h.deviceId, h.physical, "title", JsonPrimitive("v${rng.nextInt(100)}"))
            1 -> Op.SetField(nextId(), entity, EntityType.Node, h, Actor.Operator("clarify"), h.deviceId, h.physical, "summary", JsonPrimitive("s${rng.nextInt(100)}"))
            2 -> Op.Move(nextId(), entity, EntityType.Node, h, Actor.Human, h.deviceId, h.physical, entities[rng.nextInt(entities.size)])
            3 -> Op.AddTag(nextId(), entity, EntityType.Tag, h, Actor.Human, h.deviceId, h.physical, contexts[rng.nextInt(contexts.size)])
            4 -> Op.RemoveTag(nextId(), entity, EntityType.Tag, h, Actor.Human, h.deviceId, h.physical, contexts[rng.nextInt(contexts.size)])
            else -> Op.Tombstone(nextId(), entity, EntityType.Node, h, Actor.Human, h.deviceId, h.physical)
        }
    }
}
