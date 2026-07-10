package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class BridgeReadModelTest {
    private class MutableClock(var ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    private fun setup(): Pair<OpWriter, BridgeReadModel> {
        val db = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)
        val store = SqlDelightStateStore(db)
        val clock = MutableClock(1000)
        val writer = OpWriter(db, store, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(7))
        return writer to BridgeReadModel(store)
    }

    @Test
    fun inboxReflectsCapturesAndHidesCompleted() {
        val (writer, read) = setup()
        val inbox = writer.createNode("Inbox")
        val a = writer.createNode("Buy milk", parent = inbox)
        val b = writer.createNode("Call mom", parent = inbox)
        writer.setField(b, "status", JsonPrimitive("DONE"))

        val titles = read.inbox(inbox).mapNotNull { it.title }
        assertTrue(titles.contains("Buy milk"))
        assertFalse("completed items are hidden from the inbox", titles.contains("Call mom"))

        // node view exposes details + parent
        val view = read.node(a)!!
        assertEquals("Buy milk", view.title)
        assertEquals(inbox.toString(), view.parent)
    }

    @Test
    fun childrenAndTombstoneReflectInProjection() {
        val (writer, read) = setup()
        val project = writer.createNode("Project")
        writer.createNode("child 1", parent = project)
        val gone = writer.createNode("child 2", parent = project)
        assertEquals(2, read.children(project).size)

        writer.tombstone(gone)
        assertEquals(1, read.children(project).size)
        assertNull(read.node(gone)) // dead nodes not returned
    }
}
