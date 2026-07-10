package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import dev.njr.zync.data.db.ZyncDatabase
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class OpWriterTest {
    private class MutableClock(var ms: Long) : Clock {
        override fun nowMillis() = ms
    }

    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    private fun freshDb(): ZyncDatabase =
        AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)

    private fun writer(db: ZyncDatabase, store: SqlDelightStateStore, hlcStore: HlcStore, clock: Clock) =
        OpWriter(db, store, LocalHlc(hlcStore, "phone", clock), "phone", clock, Random(1))

    @Test
    fun mutationsProjectAndAreRecordedUnsynced() {
        val db = freshDb()
        val store = SqlDelightStateStore(db)
        val ops = writer(db, store, FakeHlcStore(), MutableClock(1000))

        val project = ops.createNode("Buy milk")
        val task = ops.createNode("Read book", parent = project)
        ops.setField(task, "status", JsonPrimitive("DONE"))
        val ctx = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ")
        ops.addTag(task, ctx)

        val snap = store.project()
        assertEquals(JsonPrimitive("Read book"), snap.getValue(task).fields["title"])
        assertEquals(JsonPrimitive("DONE"), snap.getValue(task).fields["status"])
        assertEquals(project, snap.getValue(task).parent)
        assertTrue(snap.getValue(task).tags.contains(ctx))

        // every mutation is queued for sync: project(title) + task(title+move) + status + tag
        val unsynced = db.transportQueries.selectUnsynced().executeAsList()
        assertEquals(5, unsynced.size)
    }

    @Test
    fun tombstoneRemovesEntity() {
        val db = freshDb()
        val store = SqlDelightStateStore(db)
        val ops = writer(db, store, FakeHlcStore(), MutableClock(1000))
        val node = ops.createNode("temp")
        ops.tombstone(node)
        assertFalse(store.project().getValue(node).alive)
    }

    @Test
    fun matchesInMemoryReferenceForSameOps() {
        val db = freshDb()
        val store = SqlDelightStateStore(db)
        val ops = writer(db, store, FakeHlcStore(), MutableClock(1000))
        val recorded = mutableListOf<dev.njr.zync.core.op.Op>()
        recorded += ops.setField(Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ"), "title", JsonPrimitive("x"))
        val reference = InMemoryStateStore().apply { recorded.forEach { dev.njr.zync.core.merge.apply(it, this) } }
        assertEquals(reference.project(), store.project())
    }

    @Test
    fun hlcIsMonotonicAcrossSimulatedRestart() {
        val db = freshDb()
        val store = SqlDelightStateStore(db)
        val hlcStore = FakeHlcStore()
        val clock = MutableClock(10_000)

        val before = writer(db, store, hlcStore, clock).setField(Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ"), "title", JsonPrimitive("a")).hlc
        // "restart": a new writer reloads the persisted HLC; wall clock has gone backwards
        clock.ms = 5_000
        val after = writer(db, store, hlcStore, clock).setField(Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ"), "notes", JsonPrimitive("b")).hlc
        assertTrue("HLC must not go backwards across restart", after > before)
    }
}
