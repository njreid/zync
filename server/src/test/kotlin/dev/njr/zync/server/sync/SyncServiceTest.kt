package dev.njr.zync.server.sync

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.Ops
import dev.njr.zync.server.RandomOps
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncServiceTest {
    private fun service() = SyncService(JvmZyncDatabase.inMemory())

    /** Apply a set of pulled ops to a fresh replica and return its projection. */
    private fun replicaProjection(ops: List<dev.njr.zync.core.op.Op>) =
        InMemoryStateStore().apply { ops.forEach { apply(it, this) } }.project()

    @Test
    fun pushAssignsSeqAndPullReturnsThemInOrder() {
        val svc = service()
        val ops = Ops()
        val t = id(1)
        val push = svc.push(PushRequest(listOf(
            ops.setField(t, "title", str("a"), hlc(10)),
            ops.setField(t, "title", str("b"), hlc(11)),
        )))
        assertEquals(2, push.serverHead)
        val pulled = svc.pull(since = 0).ops
        assertEquals(listOf(1L, 2L), pulled.map { it.seq })
        assertTrue(svc.pull(since = 2).ops.isEmpty())
    }

    @Test
    fun pullPagesByLimit() {
        val svc = service()
        val ops = Ops()
        val t = id(1)
        svc.push(PushRequest((1..5).map { ops.setField(t, "f$it", str("$it"), hlc(it.toLong())) }))
        val page1 = svc.pull(since = 0, limit = 2)
        assertEquals(listOf(1L, 2L), page1.ops.map { it.seq })
        assertEquals(5L, page1.head)
        val page2 = svc.pull(since = 2, limit = 2)
        assertEquals(listOf(3L, 4L), page2.ops.map { it.seq })
    }

    @Test
    fun rePushIsIdempotent() {
        val svc = service()
        val batch = RandomOps(3).batch(40)
        svc.push(PushRequest(batch))
        val afterFirst = svc.bootstrap()
        svc.push(PushRequest(batch)) // re-deliver everything
        val afterSecond = svc.bootstrap()
        assertEquals(afterFirst.headSeq, afterSecond.headSeq, "re-push must not advance head")
        assertEquals(afterFirst.registers.toSet(), afterSecond.registers.toSet())
        assertEquals(afterFirst.moves.toSet(), afterSecond.moves.toSet())
    }

    @Test
    fun bootstrapReflectsMergedState() {
        val svc = service()
        val ops = Ops()
        val t = id(1)
        svc.push(PushRequest(listOf(
            ops.setField(t, "title", str("keep"), hlc(10)),
            ops.tombstone(id(2), hlc(11)),
        )))
        val snap = svc.bootstrap()
        assertTrue(snap.registers.any { it.entityId == t && it.value == str("keep") })
        assertTrue(snap.tombstones.any { it.entityId == id(2) })
    }

    @Test
    fun phoneOfflineThenPushConvergesWithFreshReplica() {
        // Phone applies a batch offline, pushes on reconnect; a fresh replica that
        // pulls all server ops converges to the same projected state.
        val svc = service()
        val batch = RandomOps(11).batch(60)

        val phone = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }
        svc.push(PushRequest(batch))

        val serverOps = svc.pull(since = 0, limit = 1000).ops
        assertEquals(phone.project(), replicaProjection(serverOps), "round-trip divergence")
    }

    @Test
    fun lwwAndTombstoneResolveOverTheWire() {
        val svc = service()
        val ops = Ops()
        val t = id(1)
        svc.push(PushRequest(listOf(
            ops.setField(t, "title", str("old"), hlc(10, dev = "phone")),
            ops.setField(t, "title", str("new"), hlc(12, dev = "desk")),
            ops.tombstone(id(2), hlc(5)),
            ops.setField(id(2), "title", str("edited"), hlc(9)), // later than tombstone, still dead
        )))
        val proj = replicaProjection(svc.pull(0, 1000).ops)
        assertEquals(str("new"), proj.getValue(t).fields["title"])
        assertTrue(!proj.getValue(id(2)).alive)
    }
}
