package dev.njr.zync.web

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.views.inboxSection
import dev.njr.zync.web.views.treeSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6 acceptance: the shared UI renders identically on both surfaces from the same
 * op-log projection. The server and phone use the *same* `:web` views + read model, so
 * two stores that have seen the same ops (i.e. after sync converges) render byte-for-byte
 * identical HTML — the definition of "one content UI, shared by server and phone".
 */
class M6AcceptanceTest {
    @Test
    fun sameProjectionRendersIdenticallyOnBothSurfaces() {
        // Surface A (say, the server) authors content...
        val serverStore = InMemoryStateStore()
        val emitter = RecordingEmitter(serverStore)
        val commands = ContentCommands(emitter)
        val inbox = commands.createProject("Inbox")
        commands.createTask("Buy milk", inbox)
        val proj = commands.createProject("Launch", inbox)
        commands.addSubtask(proj, "Design")
        val done = commands.createTask("old", inbox)
        commands.complete(done)

        // ...and the phone converges by applying the same ops (as a sync pull would).
        val phoneStore = InMemoryStateStore()
        emitter.emitted.forEach { apply(it, phoneStore) }

        val serverRead = ContentReadModel(serverStore)
        val phoneRead = ContentReadModel(phoneStore)

        val serverInbox = WebPlatform.renderFragment("inbox") { inboxSection(serverRead, inbox, Long.MAX_VALUE) }
        val phoneInbox = WebPlatform.renderFragment("inbox") { inboxSection(phoneRead, inbox, Long.MAX_VALUE) }
        val serverTree = WebPlatform.renderFragment("tree") { treeSection(serverRead, null) }
        val phoneTree = WebPlatform.renderFragment("tree") { treeSection(phoneRead, null) }

        assertEquals(serverInbox, phoneInbox) // identical inbox UI on both surfaces
        assertEquals(serverTree, phoneTree)   // identical tree UI on both surfaces

        // and the content is actually there / filtered correctly
        assertTrue(serverInbox.contains("Buy milk") && serverInbox.contains("Launch"))
        assertTrue(!serverInbox.contains(">old<")) // completed task hidden from inbox
        assertTrue(serverTree.contains("Design"))  // subtask appears in the tree
    }
}
