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

        fun apply(m: Map<dev.njr.zync.core.id.Ulid, String>) = m.forEach { (n, r) -> commands.setRank(n, r) }

        // Send c to the top.
        apply(read.reorder(inbox, c, Reorder.TOP))
        assertEquals(listOf("c", "a", "b"), titles(inbox))

        // Move a down one slot (past b).
        apply(read.reorder(inbox, a, Reorder.DOWN))
        assertEquals(listOf("c", "b", "a"), titles(inbox))

        // Move a back up one slot.
        apply(read.reorder(inbox, a, Reorder.UP))
        assertEquals(listOf("c", "a", "b"), titles(inbox))
    }

    @Test
    fun reorderAtEdgesIsANoOp() {
        val inbox = commands.createProject("Inbox")
        val a = commands.createTask("a", inbox)
        val b = commands.createTask("b", inbox)
        assertTrue(read.reorder(inbox, a, Reorder.UP).isEmpty())    // already top
        assertTrue(read.reorder(inbox, a, Reorder.TOP).isEmpty())   // already top
        assertTrue(read.reorder(inbox, b, Reorder.DOWN).isEmpty())  // already bottom
    }

    @Test
    fun collidedRanksRebalanceInsteadOfCrashing() {
        val inbox = commands.createProject("Inbox")
        val a = commands.createTask("a", inbox)
        val b = commands.createTask("b", inbox)
        val c = commands.createTask("c", inbox)
        // Force a cross-device-style tie: a and b get the SAME rank ("m"); c stays FIFO
        // (ULID rank sorts before "m"), so the list is [c, a, b].
        commands.setRank(a, "m")
        commands.setRank(b, "m")
        assertEquals(listOf("c", "a", "b"), titles(inbox))

        // Move c down → its new slot is bracketed by the tied a and b → between("m","m")
        // would throw; instead the whole list rebalances. Must not crash.
        val writes = read.reorder(inbox, c, Reorder.DOWN)
        assertTrue(writes.isNotEmpty())
        writes.forEach { (n, r) -> commands.setRank(n, r) }
        assertEquals(listOf("a", "c", "b"), titles(inbox))
        assertEquals(3, read.inbox(inbox).mapNotNull { it.rank }.toSet().size) // distinct ranks
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
