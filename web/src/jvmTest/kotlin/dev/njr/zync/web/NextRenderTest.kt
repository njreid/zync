package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The /next surface renders loose + project-grouped rows and honors the context cookie. */
class NextRenderTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)
    private val inbox = commands.createProject("Inbox")

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(SSE)
            routing { webRoutes(read, inbox = { inbox }, changes = ChangeNotifier(), commands = commands) }
        }
        block(client)
    }

    @Test
    fun nextTabRendersLooseThenProjectRows() = app { client ->
        commands.createTask("triage-me", inbox) // must NOT appear
        commands.createTask("loose-action")
        val proj = commands.createProject("Kitchen")
        commands.createTask("buy tiles", proj)

        val body = client.get("/next").bodyAsText()
        assertTrue(body.contains("loose-action"))
        assertTrue(body.contains("Kitchen") && body.contains("buy tiles"))
        // The item must not appear as a next-action ROW (row-title). It may legitimately appear as
        // a File destination now, since the test's inbox is a project and triage-me its subtask.
        assertFalse(body.contains(">triage-me</span>"), "inbox item leaked into Next as a row: $body")
    }

    @Test
    fun nextRespectsContextCookie() = app { client ->
        val ctx = commands.createContext("@work")
        val tagged = commands.createTask("work item"); commands.addTag(tagged, ctx)
        commands.createTask("home item")

        val scoped = client.get("/next?context=$ctx").bodyAsText()
        assertTrue(scoped.contains("work item"))
        assertFalse(scoped.contains("home item"), "context scoping failed: $scoped")
    }
}
