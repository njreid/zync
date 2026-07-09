package dev.njr.zync.data

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

private class FixedClock(private val millis: Long) : Clock {
    override fun nowMillis(): Long = millis
}

/** Deterministic entity id from a seed. */
fun id(seed: Int): Ulid = Ulid.generate(FixedClock(seed.toLong()), Random(seed.toLong()))

/**
 * Generates a diverse op batch (all types) with globally-unique HLCs, mirroring
 * core's convergence generator — used to prove the SQLDelight store matches the
 * in-memory reference.
 */
class RandomOps(seed: Int) {
    private val rng = Random(seed)
    private val entities = (1..5).map { id(it) }
    private val contexts = (20..22).map { id(it) }
    private val usedHlc = mutableSetOf<Hlc>()
    private var opSeq = 700_000

    private fun opId(): Ulid {
        val n = opSeq++
        return Ulid.generate(FixedClock(n.toLong()), Random(n.toLong()))
    }

    private fun uniqueHlc(): Hlc {
        while (true) {
            val h = Hlc(rng.nextLong(1, 8), rng.nextInt(0, 3), listOf("A", "B", "C")[rng.nextInt(3)])
            if (usedHlc.add(h)) return h
        }
    }

    fun batch(count: Int): List<Op> = List(count) {
        val entity = entities[rng.nextInt(entities.size)]
        val hlc = uniqueHlc()
        val dev = hlc.deviceId
        when (rng.nextInt(6)) {
            0 -> Op.SetField(opId(), entity, EntityType.Node, hlc, Actor.Human, dev, hlc.physical, "title", JsonPrimitive("v${rng.nextInt(100)}"))
            1 -> Op.SetField(opId(), entity, EntityType.Node, hlc, Actor.Operator("clarify"), dev, hlc.physical, "summary", JsonPrimitive("s${rng.nextInt(100)}"))
            2 -> Op.Move(opId(), entity, EntityType.Node, hlc, Actor.Human, dev, hlc.physical, entities[rng.nextInt(entities.size)])
            3 -> Op.AddTag(opId(), entity, EntityType.Tag, hlc, Actor.Human, dev, hlc.physical, contexts[rng.nextInt(contexts.size)])
            4 -> Op.RemoveTag(opId(), entity, EntityType.Tag, hlc, Actor.Human, dev, hlc.physical, contexts[rng.nextInt(contexts.size)])
            else -> Op.Tombstone(opId(), entity, EntityType.Node, hlc, Actor.Human, dev, hlc.physical)
        }
    }
}
