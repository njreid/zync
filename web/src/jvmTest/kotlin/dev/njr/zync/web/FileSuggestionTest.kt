package dev.njr.zync.web

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.Status
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.post
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Accepting/dismissing file-location suggestions + the DONE→Reference proposal (build S5). */
class FileSuggestionTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
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
    fun acceptingAChipMovesAndClears() = app { client ->
        val task = commands.createTask("triage", inbox)
        val proj = commands.createProject("Home Reno")
        emitter.setField(
            task, Fields.FILE_SUGGESTIONS,
            buildJsonArray {
                add(buildJsonObject { put("targetId", JsonPrimitive(proj.toString())); put("title", JsonPrimitive("Home Reno")); put("tree", JsonPrimitive("projects")); put("score", JsonPrimitive(0.5)) })
            },
        )
        client.post("/node/$task/accept-file?target=$proj")
        assertEquals(proj, read.node(task)!!.parent)
        assertNull(read.node(task)!!.fileSuggestions.firstOrNull()) // field cleared
    }

    @Test
    fun dismissClearsSuggestions() = app { client ->
        val task = commands.createTask("triage", inbox)
        emitter.setField(task, Fields.FILE_SUGGESTIONS, buildJsonArray { add(buildJsonObject { put("targetId", JsonPrimitive(inbox.toString())); put("title", JsonPrimitive("x")); put("tree", JsonPrimitive("projects")); put("score", JsonPrimitive(0.2)) }) })
        client.post("/node/$task/dismiss-file")
        assertEquals(emptyList(), read.node(task)!!.fileSuggestions)
    }

    @Test
    fun acceptingProposalFilesUnderReferenceArea() = app { client ->
        val area = commands.createProject("Receipts")
        val task = commands.createTask("done thing")
        commands.complete(task)
        emitter.setField(task, Fields.PROPOSED_FILE_PARENT, JsonPrimitive(area.toString()))

        client.post("/node/$task/file-done?target=$area")
        val v = read.node(task)!!
        assertEquals(Status.FILED, v.status)
        assertEquals(area, v.parent)
        assertNull(v.proposedFileParent)
    }

    @Test
    fun rejectingProposalClearsIt() = app { client ->
        val area = commands.createProject("Receipts")
        val task = commands.createTask("done thing")
        emitter.setField(task, Fields.PROPOSED_FILE_PARENT, JsonPrimitive(area.toString()))
        client.post("/node/$task/file-done-reject")
        assertNull(read.node(task)!!.proposedFileParent)
    }
}
