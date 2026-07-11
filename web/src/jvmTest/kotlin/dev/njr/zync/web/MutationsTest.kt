package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutationsTest {
    @Test
    fun actionsApplyCommandsAndPatchInbox() = testApplication {
        val store = InMemoryStateStore()
        val commands = ContentCommands(RecordingEmitter(store))
        val read = ContentReadModel(store)
        val inbox = commands.createProject("Inbox")
        application {
            install(SSE)
            routing { webRoutes(read, inbox = { inbox }, changes = ChangeNotifier(), commands = commands) }
        }

        // create → the action returns a Datastar patch listing the new task
        val created = client.post("/inbox?title=Buy%20milk")
        assertEquals(HttpStatusCode.OK, created.status)
        val createdBody = created.bodyAsText()
        assertTrue(createdBody.contains("event: datastar-patch-elements"), createdBody)
        assertTrue(createdBody.contains("Buy milk"))
        val milk = read.inbox(inbox).first { it.title == "Buy milk" }.id

        // complete → status DONE + dropped from the patched inbox
        val completed = client.post("/node/$milk/complete").bodyAsText()
        assertEquals("DONE", read.node(milk)!!.status)
        assertFalse(completed.contains("Buy milk"))

        // trash
        client.post("/inbox?title=temp")
        val temp = read.inbox(inbox).first { it.title == "temp" }.id
        client.post("/node/$temp/trash")
        assertEquals("DROPPED", read.node(temp)!!.status)

        // defer hides from the inbox
        client.post("/inbox?title=later")
        val later = read.inbox(inbox).first { it.title == "later" }.id
        client.post("/node/$later/defer?until=9999999999999")
        assertFalse(read.inbox(inbox, now = 1000).any { it.id == later })

        // move
        val project = commands.createProject("P")
        client.post("/node/$later/move?parent=$project")
        assertEquals(project, read.node(later)!!.parent)

        // reopen brings it back to ACTIVE
        client.post("/node/$milk/reopen")
        assertEquals("ACTIVE", read.node(milk)!!.status)

        // empty title rejected
        assertEquals(HttpStatusCode.BadRequest, client.post("/inbox?title=").status)
    }
}
