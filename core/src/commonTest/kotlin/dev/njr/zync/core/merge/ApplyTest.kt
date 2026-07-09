package dev.njr.zync.core.merge

import dev.njr.zync.core.OpFactory
import dev.njr.zync.core.hlc
import dev.njr.zync.core.id
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.state.TagKey
import dev.njr.zync.core.str
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyTest {
    private val ops = OpFactory()
    private val T = id(1)
    private val ctx = id(2)

    private fun store(vararg op: Op) = InMemoryStateStore().apply { op.forEach { apply(it, this) } }

    @Test
    fun higherHlcWins() {
        val s = store(
            ops.setField(T, "title", str("buy milk"), hlc(10)),
            ops.setField(T, "title", str("buy oat milk"), hlc(12)),
        )
        assertEquals(str("buy oat milk"), s.getRegister(RegisterKey(T, "title"))?.value)
    }

    @Test
    fun lowerHlcIsIgnoredRegardlessOfArrivalOrder() {
        val s = store(
            ops.setField(T, "title", str("winner"), hlc(20)),
            ops.setField(T, "title", str("loser"), hlc(5)),
        )
        assertEquals(str("winner"), s.getRegister(RegisterKey(T, "title"))?.value)
    }

    @Test
    fun applyTwiceEqualsApplyOnce() {
        val op = ops.setField(T, "title", str("x"), hlc(10))
        val once = InMemoryStateStore().apply { apply(op, this) }
        val twice = InMemoryStateStore().apply { apply(op, this); apply(op, this) }
        assertEquals(once.project(), twice.project())
    }

    @Test
    fun nonMoveOpsAreOrderIndependent() {
        val batch = listOf(
            ops.setField(T, "title", str("A"), hlc(10, dev = "p")),
            ops.setField(T, "title", str("B"), hlc(10, dev = "q")), // same physical, device tiebreak
            ops.setField(T, "notes", str("n"), hlc(11)),
            ops.setField(id(3), "name", str("ctx"), hlc(9)),
            ops.addTag(T, ctx, hlc(8)),
            ops.removeTag(T, ctx, hlc(12)),
            ops.tombstone(id(3), hlc(15)),
        )
        val forward = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }
        val shuffled = InMemoryStateStore().apply { batch.shuffled(Random(42)).forEach { apply(it, this) } }
        assertEquals(forward.project(), shuffled.project())
        // device tiebreak: "q" > "p" at equal physical/counter
        assertEquals(str("B"), forward.getRegister(RegisterKey(T, "title"))?.value)
    }

    @Test
    fun tombstoneBeatsConcurrentEditAtProjection() {
        // V7: edit HLC (12) is later than tombstone (10), but tombstone is terminal.
        val s = store(
            ops.tombstone(T, hlc(10, dev = "A")),
            ops.setField(T, "title", str("X"), hlc(12, dev = "B")),
        )
        assertFalse(s.project().getValue(T).alive)
        // the register may physically hold the later edit; existence still reports dead
        assertEquals(str("X"), s.getRegister(RegisterKey(T, "title"))?.value)
    }

    @Test
    fun tombstoneKeepsMaxHlc() {
        val s = store(ops.tombstone(T, hlc(20)), ops.tombstone(T, hlc(5)))
        assertEquals(hlc(20), s.getTombstone(T))
    }

    @Test
    fun tagLwwRemoveWinsWhenLater() {
        // V8
        val s = store(ops.addTag(T, ctx, hlc(10, dev = "A")), ops.removeTag(T, ctx, hlc(12, dev = "B")))
        assertFalse(s.project().getValue(T).tags.contains(ctx))
    }

    @Test
    fun tagLwwAddWinsWhenLater() {
        // V8 variant
        val s = store(ops.addTag(T, ctx, hlc(13, dev = "A")), ops.removeTag(T, ctx, hlc(12, dev = "B")))
        // T only has a tag, no register → not "alive", but the tag membership is present
        assertTrue(s.getTag(TagKey(T, ctx))!!.present)
    }

    @Test
    fun attachmentIsAddedAndRemovableByTombstone() {
        val att = id(9)
        val added = store(ops.addAttachment(att, str("blob"), hlc(10)))
        assertTrue(added.project().getValue(att).alive)
        val removed = store(ops.addAttachment(att, str("blob"), hlc(10)), ops.tombstone(att, hlc(11)))
        assertFalse(removed.project().getValue(att).alive)
    }
}
