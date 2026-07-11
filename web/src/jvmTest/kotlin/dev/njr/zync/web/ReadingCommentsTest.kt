package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.get
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

class ReadingCommentsTest {
    @Test
    fun subtasksCommentsAndReadingView() = testApplication {
        val store = InMemoryStateStore()
        val commands = ContentCommands(RecordingEmitter(store))
        val read = ContentReadModel(store)
        val project = commands.createProject("Launch")
        commands.setNotes(project, "First paragraph.\n\nSecond paragraph.")

        application {
            install(SSE)
            routing { webRoutes(read, changes = ChangeNotifier(), commands = commands) }
        }

        // decompose: add a subtask → patch lists it under Subtasks
        val subResp = client.post("/node/$project/subtask?title=Design").bodyAsText()
        assertTrue(subResp.contains("event: datastar-patch-elements"))
        assertTrue(subResp.contains("Design") && subResp.contains("Subtasks"))
        assertEquals(1, read.children(project).size)

        // comment → shows under Comments, and is NOT counted as a subtask
        client.post("/node/$project/comment?text=Ship%20by%20Q3")
        assertTrue(client.get("/node/$project").bodyAsText().contains("Ship by Q3"))
        assertEquals(1, read.comments(project).size)
        assertEquals(1, read.children(project).size) // still just the subtask

        // reading view renders notes as prose paragraphs
        val reading = client.get("/node/$project/read").bodyAsText()
        assertTrue(reading.contains("<p>First paragraph.</p>"))
        assertTrue(reading.contains("<p>Second paragraph.</p>"))

        // empty comment rejected
        assertEquals(HttpStatusCode.BadRequest, client.post("/node/$project/comment?text=").status)
        assertFalse(client.get("/node/$project").bodyAsText().contains("(untitled)"))
    }
}
