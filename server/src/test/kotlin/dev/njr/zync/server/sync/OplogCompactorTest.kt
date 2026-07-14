package dev.njr.zync.server.sync

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.core.state.RegisterValue
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.core.state.TagKey
import dev.njr.zync.core.state.TagValue
import dev.njr.zync.core.sync.BootstrapSnapshot
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.Ops
import dev.njr.zync.server.RandomOps
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import dev.njr.zync.server.zyncModule
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seed a fresh replica from a bootstrap snapshot (registers/tombstones/tags direct, moves via apply). */
private fun seed(store: StateStore, snap: BootstrapSnapshot) {
    for (r in snap.registers) store.putRegister(RegisterKey(r.entityId, r.field), RegisterValue(r.value, r.hlc, r.actor))
    for (t in snap.tombstones) store.putTombstone(t.entityId, t.hlc)
    for (t in snap.tags) store.putTag(TagKey(t.nodeId, t.contextId), TagValue(t.present, t.hlc))
    for (m in snap.moves) apply(m, store)
}

class OplogCompactorTest {
    @Test
    fun compactionPreservesProjectedStateAndBootstrapEquivalence() {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        // 60, not more: RandomOps can mint at most 63 unique HLCs per instance.
        svc.push(PushRequest(RandomOps(7).batch(60)))
        val before = svc.state()

        val compactor = OplogCompactor(db, CompactionPolicy(retainOps = 10, retainMillis = 0))
        val result = compactor.compactOnce()

        assertTrue(result.deleted > 0, "compaction deleted rows")
        assertEquals(before, svc.state(), "projected merge state identical before/after compaction")
        assertEquals(10L, db.transportQueries.countOps().executeAsOne(), "retainOps newest ops kept")
        assertEquals(result.floor, compactor.floor(), "floor recorded")

        // Fresh-replica equivalence: bootstrap snapshot + seq tail converges to the same state.
        val snap = svc.bootstrap()
        val fresh = InMemoryStateStore()
        seed(fresh, snap)
        for (op in svc.pull(since = snap.headSeq).ops) apply(op, fresh)
        assertEquals(before, fresh.project(), "bootstrap + tail replica converges to pre-compaction state")

        // A replica whose cursor is at the floor can still tail the retained ops.
        assertEquals(10, svc.pull(since = result.floor).ops.size)
    }

    @Test
    fun ageRetentionProtectsRecentlyAuthoredOps() {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        val now = 1_000_000L
        val ops = Ops()
        // 3 old ops (wallClock = hlc.physical, well before the window), then 2 recent ones.
        svc.push(PushRequest(listOf(
            ops.setField(id(1), "title", str("a"), hlc(10)),
            ops.setField(id(1), "notes", str("b"), hlc(20)),
            ops.setField(id(2), "title", str("c"), hlc(30)),
            ops.setField(id(2), "notes", str("d"), hlc(now - 50)),
            ops.setField(id(3), "title", str("e"), hlc(now - 10)),
        )))
        val before = svc.state()

        val compactor = OplogCompactor(db, CompactionPolicy(retainOps = 0, retainMillis = 100), now = { now })
        val result = compactor.compactOnce()

        assertEquals(3L, result.floor, "floor sits just below the first op inside the age window")
        assertEquals(2L, db.transportQueries.countOps().executeAsOne(), "recent ops survive")
        assertEquals(before, svc.state())
    }

    @Test
    fun peerCursorClampsTheFloor() {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        svc.push(PushRequest(RandomOps(3).batch(20)))
        db.transportQueries.setCursor("phone", 5)

        val result = OplogCompactor(db, CompactionPolicy(retainOps = 0, retainMillis = 0)).compactOnce()

        assertEquals(5L, result.floor, "never compact past a known peer cursor")
        assertEquals(15L, db.transportQueries.countOps().executeAsOne())
        assertEquals(15, svc.pull(since = 5).ops.size, "the lagging peer can still pull everything it needs")
    }

    @Test
    fun floorIsMonotonicAndRepeatRunsAreNoops() {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        svc.push(PushRequest(RandomOps(11).batch(30)))

        val compactor = OplogCompactor(db, CompactionPolicy(retainOps = 5, retainMillis = 0))
        val first = compactor.compactOnce()
        val second = compactor.compactOnce()

        assertTrue(first.deleted > 0)
        assertEquals(0L, second.deleted, "second pass has nothing to delete")
        assertEquals(first.floor, second.floor, "floor does not move without new ops")
    }

    @Test
    fun redeliveryOfACompactedOpStaysAMergeNoop() {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        val op = Ops().setField(id(9), "title", str("keep"), hlc(5))
        svc.push(PushRequest(listOf(op)))
        val before = svc.state()

        OplogCompactor(db, CompactionPolicy(retainOps = 0, retainMillis = 0)).compactOnce()
        svc.push(PushRequest(listOf(op))) // phone retries an op the server already applied + compacted

        assertEquals(before, svc.state(), "applied_op ledger keeps re-delivery idempotent")
    }

    @Test
    fun pullBelowTheFloorIsGone() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        svc.push(PushRequest(RandomOps(5).batch(20)))
        val compactor = OplogCompactor(db, CompactionPolicy(retainOps = 5, retainMillis = 0))
        val floor = compactor.compactOnce().floor
        application { zyncModule(svc, compactionFloor = compactor::floor) }

        assertEquals(HttpStatusCode.Gone, client.get("/sync/pull?since=0").status)
        assertEquals(HttpStatusCode.Gone, client.get("/sync/pull?since=${floor - 1}").status)
        assertEquals(HttpStatusCode.OK, client.get("/sync/pull?since=$floor").status)
    }
}
