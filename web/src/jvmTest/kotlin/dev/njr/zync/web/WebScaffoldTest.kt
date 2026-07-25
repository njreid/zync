package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebScaffoldTest {
    @Test
    fun rendersInboxTreeDetailAndHealth() = testApplication {
        val store = InMemoryStateStore()
        val commands = ContentCommands(RecordingEmitter(store))
        val inbox = commands.createProject("Inbox")
        commands.createTask("Buy milk", parent = inbox) // an Inbox Item (leaf)
        val proj = commands.createTask("Groceries", parent = inbox)
        commands.createTask("Read book", parent = proj) // a subtask ⇒ Groceries is a Project
        val read = ContentReadModel(store)

        application { routing { webRoutes(read, inbox = { inbox }) } }

        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("Buy milk"), "inbox should list the item: $home")
        assertTrue(home.contains("<h2>Inbox</h2>"))

        val tree = client.get("/tree").bodyAsText()
        assertTrue(tree.contains("Groceries") && tree.contains("Read book"))

        val detail = client.get("/node/$proj").bodyAsText()
        assertTrue(detail.contains("Groceries"))
        assertTrue(detail.contains("Subtasks") && detail.contains("Read book"))

        assertEquals(HttpStatusCode.NotFound, client.get("/node/not-a-ulid").status)
    }
}
