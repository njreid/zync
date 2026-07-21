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

/**
 * The server-render contract the gesture helper (zync-gestures.js) depends on: the
 * asset is served, the page wires it, and inbox rows carry the swipe-row wrapper +
 * hidden complete/trash triggers (GTD triage §4, build S2).
 */
class GesturesServingTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)
    private val inbox = commands.createProject("Inbox")

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { routing { webRoutes(read, inbox = { inbox }, commands = commands) } }
        block(client)
    }

    @Test
    fun assetIsServedAndPageWiresIt() = app { client ->
        val js = client.get("/assets/zync-gestures.js")
        assertEquals(HttpStatusCode.OK, js.status)
        val body = js.bodyAsText()
        assertTrue(body.contains("swipe-fire"), "gesture helper body looks wrong")

        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("src=\"/assets/zync-gestures.js\""), "page should load the gesture module")
        // g-chord tab keys are read from the DOM.
        assertTrue(home.contains("data-key=\"n\"") && home.contains("data-key=\"p\""))
    }

    @Test
    fun inboxRowsCarrySwipeContract() = app { client ->
        commands.createTask("triage me", inbox)
        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("class=\"swipe-row\""), "inbox rows need the swipe-row wrapper: $home")
        assertTrue(home.contains("data-node="))
        assertTrue(home.contains("class=\"swipe-fire complete\""))
        assertTrue(home.contains("class=\"swipe-fire trash\""))
        // The hidden triggers reuse the existing complete/trash endpoints.
        assertTrue(home.contains("@post('/node/") && home.contains("/complete')") && home.contains("/trash')"))
    }
}
