package dev.njr.zync.core.merge

import dev.njr.zync.core.OpFactory
import dev.njr.zync.core.hlc
import dev.njr.zync.core.id
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.str
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical merge conformance vectors V1–V8 from
 * `docs/superpowers/specs/2026-07-08-merge-conformance-vectors.md`. Each vector is
 * checked for its expected converged state AND that the state is identical when the
 * ops are delivered in the reverse order (a sample of the general shuffle property).
 */
class ConformanceTest {
    private val ops = OpFactory()
    private val t = id(1)

    /** Apply [batch] in the given order and (separately) reversed; return both stores. */
    private fun bothOrders(batch: List<Op>): Pair<InMemoryStateStore, InMemoryStateStore> {
        val forward = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }
        val reversed = InMemoryStateStore().apply { batch.reversed().forEach { apply(it, this) } }
        assertEquals(forward.project(), reversed.project(), "delivery order changed the state")
        return forward to reversed
    }

    @Test
    fun v1_lwwFieldRace() {
        val (s, _) = bothOrders(
            listOf(
                ops.setField(t, "title", str("buy milk"), hlc(1, dev = "srv")),
                ops.setField(t, "title", str("Buy oat milk"), hlc(10, dev = "phone")),
                ops.setField(t, "title", str("Buy almond milk"), hlc(12, dev = "desk")),
            ),
        )
        assertEquals(str("Buy almond milk"), s.getRegister(RegisterKey(t, "title"))?.value)
    }

    @Test
    fun v1_variant_phoneWinsWithHigherHlc() {
        val (s, _) = bothOrders(
            listOf(
                ops.setField(t, "title", str("Buy oat milk"), hlc(13, dev = "phone")),
                ops.setField(t, "title", str("Buy almond milk"), hlc(12, dev = "desk")),
            ),
        )
        assertEquals(str("Buy oat milk"), s.getRegister(RegisterKey(t, "title"))?.value)
    }

    @Test
    fun v2_moveAndIndependentEditBothApply() {
        val inbox = id(100)
        val p1 = id(101)
        val (s, _) = bothOrders(
            listOf(
                ops.move(t, inbox, hlc(1, dev = "srv")), // init parent=Inbox
                ops.setField(t, "status", str("ACTIVE"), hlc(1, dev = "srv")),
                ops.move(t, p1, hlc(10, dev = "phone")),
                ops.setField(t, "status", str("DONE"), hlc(12, dev = "srv")),
            ),
        )
        assertEquals(p1, s.getParent(t))
        assertEquals(str("DONE"), s.getRegister(RegisterKey(t, "status"))?.value)
    }

    @Test
    fun v3_moveCycleOneWinner() {
        val a = id(2)
        val b = id(3)
        val (s, _) = bothOrders(
            listOf(
                ops.move(a, b, hlc(11, dev = "phone")),
                ops.move(b, a, hlc(12, dev = "desk")),
            ),
        )
        assertEquals(b, s.getParent(a))
        assertEquals(null, s.getParent(b))
    }

    @Test
    fun v4_operatorFieldOwnershipDifferentRegisters() {
        // Merge outcome: title (human) and summary (operator-owned) are independent
        // registers, both apply — even though the operator's HLC is deliberately later.
        // The emission guard (operator may not target `title`) is write-scope enforced
        // in the operator runtime (M8); its pure predicate is covered in Task 7.
        val (s, _) = bothOrders(
            listOf(
                ops.setField(t, "title", str("X0"), hlc(1, dev = "srv")),
                ops.setField(t, "title", str("X"), hlc(12, dev = "phone"), actor = Actor.Human),
                ops.setField(t, "summary", str("Y"), hlc(13, dev = "srv"), actor = Actor.Operator("clarify")),
            ),
        )
        assertEquals(str("X"), s.getRegister(RegisterKey(t, "title"))?.value)
        assertEquals(str("Y"), s.getRegister(RegisterKey(t, "summary"))?.value)
    }

    @Test
    fun v5_outOfOrderCreatesAllAlive() {
        val n1 = id(11); val n2 = id(12); val n3 = id(13); val n9 = id(19)
        val batch = listOf(
            ops.setField(n1, "title", str("N1"), hlc(10, dev = "phone")),
            ops.setField(n2, "title", str("N2"), hlc(11, dev = "phone")),
            ops.setField(n3, "title", str("N3"), hlc(12, dev = "phone")),
            ops.setField(n9, "title", str("N9"), hlc(20, dev = "srv")),
        )
        // shuffled delivery
        val s = InMemoryStateStore().apply { batch.shuffled(Random(3)).forEach { apply(it, this) } }
        for (n in listOf(n1, n2, n3, n9)) assertTrue(s.project().getValue(n).alive, "$n should be alive")
    }

    @Test
    fun v6_trashVsEditNothingLost() {
        val (s, _) = bothOrders(
            listOf(
                ops.setField(t, "notes", str("remember oat"), hlc(10, dev = "phone")),
                ops.setField(t, "status", str("DROPPED"), hlc(12, dev = "srv")),
            ),
        )
        assertEquals(str("remember oat"), s.getRegister(RegisterKey(t, "notes"))?.value)
        assertEquals(str("DROPPED"), s.getRegister(RegisterKey(t, "status"))?.value)
    }

    @Test
    fun v7_tombstoneWinsOverLaterEdit() {
        val (s, _) = bothOrders(
            listOf(
                ops.tombstone(t, hlc(10, dev = "A")),
                ops.setField(t, "title", str("X"), hlc(12, dev = "B")),
            ),
        )
        assertFalse(s.project().getValue(t).alive)
        // the register physically holds the later value; existence still reports dead
        assertEquals(str("X"), s.getRegister(RegisterKey(t, "title"))?.value)
    }

    @Test
    fun v8_tagLwwRemoveWins() {
        val ctx = id(50)
        val (s, _) = bothOrders(
            listOf(
                ops.addTag(t, ctx, hlc(10, dev = "A")),
                ops.removeTag(t, ctx, hlc(12, dev = "B")),
            ),
        )
        assertFalse(ctx in s.project().getValue(t).tags)
    }

    @Test
    fun v8_variant_tagLwwAddWins() {
        val ctx = id(50)
        val (s, _) = bothOrders(
            listOf(
                ops.addTag(t, ctx, hlc(13, dev = "A")),
                ops.removeTag(t, ctx, hlc(12, dev = "B")),
            ),
        )
        assertTrue(ctx in s.project().getValue(t).tags)
    }
}
