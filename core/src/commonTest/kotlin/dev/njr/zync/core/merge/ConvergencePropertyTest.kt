package dev.njr.zync.core.merge

import dev.njr.zync.core.clock.FixedClock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The general convergence guarantee behind the eight fixed vectors (spec §"Property"):
 * for any generated op set — including moves, tombstones and tags — delivering the
 * same ops to two replicas in independently shuffled orders yields byte-identical
 * projected state. Plus idempotency: re-delivering ops never changes the result.
 *
 * Seeded RNG for reproducibility. HLCs are kept globally unique (as the real system
 * guarantees) so the merge has a strict total order to resolve on.
 */
class ConvergencePropertyTest {
    private val devices = listOf("A", "B", "C")
    private val entities = (1..5).map { id(it) }
    private val contexts = (20..22).map { id(it) }

    private class Gen(seed: Int) {
        val rng = Random(seed)
        private val usedHlc = mutableSetOf<Hlc>()
        private var opSeq = 0

        fun uniqueHlc(): Hlc {
            while (true) {
                val h = Hlc(rng.nextLong(1, 8), rng.nextInt(0, 3), listOf("A", "B", "C")[rng.nextInt(3)])
                if (usedHlc.add(h)) return h
            }
        }

        fun opId(): Ulid {
            val n = 500_000 + opSeq++
            return Ulid.generate(FixedClock(n.toLong()), Random(n.toLong()))
        }
    }

    private fun generate(seed: Int, count: Int): List<Op> {
        val g = Gen(seed)
        val out = ArrayList<Op>(count)
        repeat(count) {
            val entity = entities[g.rng.nextInt(entities.size)]
            val hlc = g.uniqueHlc()
            val op: Op = when (g.rng.nextInt(6)) {
                0 -> Op.SetField(g.opId(), entity, EntityType.Node, hlc, Actor.Human, hlc.deviceId, hlc.physical, "title", JsonPrimitive("v${g.rng.nextInt(100)}"))
                1 -> Op.SetField(g.opId(), entity, EntityType.Node, hlc, Actor.Human, hlc.deviceId, hlc.physical, "status", JsonPrimitive(listOf("ACTIVE", "DONE", "DROPPED")[g.rng.nextInt(3)]))
                2 -> Op.Move(g.opId(), entity, EntityType.Node, hlc, Actor.Human, hlc.deviceId, hlc.physical, entities[g.rng.nextInt(entities.size)])
                3 -> Op.AddTag(g.opId(), entity, EntityType.Tag, hlc, Actor.Human, hlc.deviceId, hlc.physical, contexts[g.rng.nextInt(contexts.size)])
                4 -> Op.RemoveTag(g.opId(), entity, EntityType.Tag, hlc, Actor.Human, hlc.deviceId, hlc.physical, contexts[g.rng.nextInt(contexts.size)])
                else -> Op.Tombstone(g.opId(), entity, EntityType.Node, hlc, Actor.Human, hlc.deviceId, hlc.physical)
            }
            out += op
        }
        return out
    }

    @Test
    fun twoReplicasConvergeUnderIndependentShuffle() {
        for (seed in 1..40) {
            val batch = generate(seed, count = 60)
            val r1 = InMemoryStateStore().apply { batch.shuffled(Random(seed * 2)).forEach { apply(it, this) } }
            val r2 = InMemoryStateStore().apply { batch.shuffled(Random(seed * 2 + 1)).forEach { apply(it, this) } }
            assertEquals(r1.project(), r2.project(), "divergence at seed=$seed")
            assertEquals(r1.allParents(), r2.allParents(), "parent divergence at seed=$seed")
        }
    }

    @Test
    fun redeliveringOpsIsIdempotent() {
        for (seed in 1..20) {
            val batch = generate(seed, count = 40)
            val once = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }
            val duplicated = InMemoryStateStore().apply {
                batch.shuffled(Random(seed)).forEach { apply(it, this) }
                batch.shuffled(Random(seed + 99)).forEach { apply(it, this) } // deliver everything again
            }
            assertEquals(once.project(), duplicated.project(), "idempotency broken at seed=$seed")
        }
    }
}
