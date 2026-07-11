package dev.njr.zync.replica

import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.ZyncApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M7 Task 1: the op-log stack is wired into ZyncApp — the shared :web ContentCommands/
 * ContentReadModel run against the app's real SQLDelight store + OpWriter. (Loopback
 * serving :web + retiring vanilla-JS is the rest of Task 1–2.)
 */
@RunWith(RobolectricTestRunner::class)
class ZyncAppOpLogTest {
    @Test
    fun appContentStackDrivesTheOpLog() {
        val app = ApplicationProvider.getApplicationContext<ZyncApp>()

        val task = app.contentCommands.createTask("Buy milk")
        assertTrue(app.contentRead.inbox(null).any { it.id == task })

        // mutations queue unsynced for the sync client
        assertTrue(app.opDatabase.transportQueries.selectUnsynced().executeAsList().isNotEmpty())

        app.contentCommands.complete(task)
        assertFalse(app.contentRead.inbox(null).any { it.id == task })

        // a capture also lands in the op log
        val captured = app.replicaCapture.captureNote("Call mom")
        assertTrue(app.contentRead.node(captured)!!.title == "Call mom")
    }
}
