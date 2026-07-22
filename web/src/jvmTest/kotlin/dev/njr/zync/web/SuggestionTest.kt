package dev.njr.zync.web

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.KIND_SUGGESTION
import dev.njr.zync.core.id.Ulid
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bot-proposed field-edit suggestions: the diff read model + accept/reject (external-op-api §4). */
class SuggestionTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
    private val read = ContentReadModel(store)

    private fun seedSuggestion(target: Ulid, field: String, value: String): Ulid {
        val sug = emitter.newId()
        emitter.setField(sug, Fields.KIND, JsonPrimitive(KIND_SUGGESTION))
        emitter.setField(sug, Fields.TARGET_ID, JsonPrimitive(target.toString()))
        emitter.setField(sug, Fields.TARGET_FIELD, JsonPrimitive(field))
        emitter.setField(sug, Fields.PROPOSED_VALUE, JsonPrimitive(value))
        emitter.setField(sug, AgentFlow.FIELD_PROPOSED, JsonPrimitive(true))
        return sug
    }

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(SSE)
            routing { webRoutes(read, changes = ChangeNotifier(), commands = commands) }
        }
        block(client)
    }

    @Test
    fun suggestionSurfacesAsADiffAndIsExcludedFromProposals() {
        val task = commands.createTask("Buy milk")
        seedSuggestion(task, "notes", "from a bot")
        val s = read.suggestions().single()
        assertEquals("Buy milk", s.targetTitle)
        assertEquals("notes", s.field)
        assertEquals("from a bot", (s.proposedValue as JsonPrimitive).content)
        assertTrue(read.proposals().isEmpty(), "suggestions render separately, not in proposals")
    }

    @Test
    fun acceptAppliesTheEditAndTombstonesTheSuggestion() = app { client ->
        val task = commands.createTask("Buy milk")
        val sug = seedSuggestion(task, "notes", "accepted note")
        client.post("/suggestion/$sug/accept")
        assertEquals("accepted note", read.node(task)!!.notes)
        assertTrue(read.suggestions().isEmpty())
        assertNull(read.node(sug)) // tombstoned
    }

    @Test
    fun rejectDropsTheSuggestionWithoutChangingTheTarget() = app { client ->
        val task = commands.createTask("Buy milk")
        val sug = seedSuggestion(task, "notes", "rejected note")
        client.post("/suggestion/$sug/reject")
        assertNull(read.node(task)!!.notes)
        assertTrue(read.suggestions().isEmpty())
    }
}
