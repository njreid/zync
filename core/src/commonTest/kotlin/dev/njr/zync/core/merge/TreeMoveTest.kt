package dev.njr.zync.core.merge

import dev.njr.zync.core.OpFactory
import dev.njr.zync.core.hlc
import dev.njr.zync.core.id
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TreeMoveTest {
    private val ops = OpFactory()
    private val a = id(1)
    private val b = id(2)
    private val c = id(3)
    private val p = id(4)

    private fun storeOf(moves: List<Op>) = InMemoryStateStore().apply { moves.forEach { apply(it, this) } }

    /** Walk each node's parent chain; must terminate at a root within the node count (no cycle). */
    private fun assertAcyclic(store: InMemoryStateStore) {
        val parents = store.allParents()
        for (node in parents.keys) {
            var cursor: Ulid? = node
            var steps = 0
            while (cursor != null) {
                cursor = parents[cursor]
                check(steps++ <= parents.size) { "cycle reached from $node" }
            }
        }
    }

    @Test
    fun concurrentCycleResolvesToOneWinnerBothOrders() {
        // V3: M1 A→B @(11), M2 B→A @(12). Later move (M2) would cycle → skipped.
        val m1 = ops.move(a, b, hlc(11, dev = "phone"))
        val m2 = ops.move(b, a, hlc(12, dev = "desk"))
        for (order in listOf(listOf(m1, m2), listOf(m2, m1))) {
            val s = storeOf(order)
            assertEquals(b, s.getParent(a), "order=$order")
            assertNull(s.getParent(b), "order=$order")
            assertAcyclic(s)
        }
    }

    @Test
    fun lateLowerHlcMoveReintegrates() {
        // Deliver the higher-HLC move first, then the earlier one; final state must match HLC order.
        val early = ops.move(a, b, hlc(11))
        val late = ops.move(b, a, hlc(12))
        val s = storeOf(listOf(late, early)) // late(12) delivered before early(11)
        assertEquals(b, s.getParent(a))
        assertNull(s.getParent(b))
    }

    @Test
    fun movingUnderOwnDescendantIsSkipped() {
        // B under A, then try to move A under B → would nest A beneath its own child → skip.
        val s = storeOf(listOf(ops.move(b, a, hlc(10)), ops.move(a, b, hlc(11))))
        assertNull(s.getParent(a))
        assertEquals(a, s.getParent(b))
        assertAcyclic(s)
    }

    @Test
    fun independentMovesBothApply() {
        val s = storeOf(listOf(ops.move(a, p, hlc(10)), ops.move(b, p, hlc(11))))
        assertEquals(p, s.getParent(a))
        assertEquals(p, s.getParent(b))
    }

    @Test
    fun laterMoveOfSameNodeWins() {
        // Re-parenting the same node twice: latest HLC wins.
        val s = storeOf(listOf(ops.move(a, b, hlc(10)), ops.move(a, p, hlc(20))))
        assertEquals(p, s.getParent(a))
    }

    @Test
    fun chainMoveConvergesUnderShuffle() {
        val moves = listOf(
            ops.move(a, b, hlc(10)),
            ops.move(b, c, hlc(11)),
            ops.move(c, p, hlc(12)),
        )
        val forward = storeOf(moves)
        val shuffled = storeOf(moves.shuffled(Random(7)))
        assertEquals(forward.allParents(), shuffled.allParents())
        assertEquals(b, forward.getParent(a))
        assertEquals(c, forward.getParent(b))
        assertEquals(p, forward.getParent(c))
        assertAcyclic(forward)
    }

    @Test
    fun threePartyCycleAttemptStaysAcyclic() {
        // A→B, B→C, C→A (last would close a cycle) → C's move skipped.
        val s = storeOf(listOf(ops.move(a, b, hlc(10)), ops.move(b, c, hlc(11)), ops.move(c, a, hlc(12))))
        assertEquals(b, s.getParent(a))
        assertEquals(c, s.getParent(b))
        assertNull(s.getParent(c))
        assertAcyclic(s)
    }
}
