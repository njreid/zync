package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.content.Reorder
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FIFO inbox order + fractional-rank reorder (GTD triage §3, build order #2).
 */
class ReorderTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    private fun titles(inbox: dev.njr.zync.core.id.Ulid) = read.inbox(inbox).map { it.title }

    @Test
    fun unrankedInboxIsFifoByCaptureOrder() {
        val inbox = commands.createProject("Inbox")
        commands.createTask("first", inbox)
        commands.createTask("second", inbox)
        commands.createTask("third", inbox)
        assertEquals(listOf("first", "second", "third"), titles(inbox))
    }

    @Test
    fun sendToTopMoveUpMoveDownRewriteOrder() {
        val inbox = commands.createProject("Inbox")
        val a = commands.createTask("a", inbox)
        commands.createTask("b", inbox)
        val c = commands.createTask("c", inbox)
        assertEquals(listOf("a", "b", "c"), titles(inbox))

        // Send c to the top.
        commands.setRank(c, read.reorderRank(inbox, c, Reorder.TOP)!!)
        assertEquals(listOf("c", "a", "b"), titles(inbox))

        // Move a down one slot (past b).
        commands.setRank(a, read.reorderRank(inbox, a, Reorder.DOWN)!!)
        assertEquals(listOf("c", "b", "a"), titles(inbox))

        // Move a back up one slot.
        commands.setRank(a, read.reorderRank(inbox, a, Reorder.UP)!!)
        assertEquals(listOf("c", "a", "b"), titles(inbox))
    }

    @Test
    fun reorderAtEdgesIsANoOp() {
        val inbox = commands.createProject("Inbox")
        val a = commands.createTask("a", inbox)
        val b = commands.createTask("b", inbox)
        assertNull(read.reorderRank(inbox, a, Reorder.UP))    // already top
        assertNull(read.reorderRank(inbox, a, Reorder.TOP))   // already top
        assertNull(read.reorderRank(inbox, b, Reorder.DOWN))  // already bottom
    }

    @Test
    fun reorderRouteRewritesInboxFragment() = testApplication {
        val inbox = commands.createProject("Inbox")
        commands.createTask("a", inbox)
        commands.createTask("b", inbox)
        val c = commands.createTask("c", inbox)

        application {
            install(SSE)
            routing { webRoutes(read, inbox = { inbox }, changes = ChangeNotifier(), commands = commands) }
        }

        val body = client.post("/node/$c/rank?dir=top").bodyAsText()
        assertTrue(body.contains("event: datastar-patch-elements"))
        // c now sorts before a in the read model.
        assertEquals(listOf("c", "a", "b"), titles(inbox))
    }
}
