package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * M6 Task 8: the shared web UI's `ContentCommands`/`ContentReadModel` run on the phone
 * over the op log — mutations go through OpWriter (queued unsynced) and reflect in the
 * read model, exactly as on the server. (Full loopback cutover / vanilla-JS retirement
 * is a deferred destructive pass; this proves the phone content path.)
 */
@RunWith(RobolectricTestRunner::class)
class PhoneContentTest {
    private class MutableClock(var ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    @Test
    fun phoneDrivesWebContentThroughOpLog() {
        val db = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)
        val store = SqlDelightStateStore(db)
        val clock = MutableClock(1000)
        val writer = OpWriter(db, store, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(1))
        val commands = ContentCommands(PhoneOpEmitter(writer))
        val read = ContentReadModel(store)

        val inbox = commands.createProject("Inbox")
        val task = commands.createTask("Buy milk", inbox)

        assertTrue(read.inbox(inbox).any { it.title == "Buy milk" })
        // mutations are queued unsynced — they push to the server on reconnect
        assertTrue(db.transportQueries.selectUnsynced().executeAsList().isNotEmpty())

        commands.complete(task)
        assertFalse(read.inbox(inbox).any { it.id == task })
        assertTrue(read.node(task)!!.status == "DONE")
    }
}
