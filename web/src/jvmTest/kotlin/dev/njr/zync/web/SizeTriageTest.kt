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
import kotlin.test.assertTrue

/** The triage routes + depth guard + panel render contract (GTD triage §4/§8, build S3). */
class SizeTriageTest {
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
    fun sizeRoute() = app { client ->
        val t = commands.createTask("t", inbox)
        val ok = client.post("/node/$t/size?size=M")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("datastar-patch-elements"))
        assertEquals("M", read.node(t)!!.size)
        assertEquals(HttpStatusCode.BadRequest, client.post("/node/$t/size?size=XL").status)
        client.post("/node/$t/size?size=") // clear
        assertEquals(null, read.node(t)!!.size)
    }

    @Test
    fun renameSplitNotes() = app { client ->
        val t = commands.createTask("old", inbox)
        client.post("/node/$t/rename?title=new")
        assertEquals("new", read.node(t)!!.title)
        client.post("/node/$t/split?title=child")
        assertEquals("project", read.node(t)!!.kind)
        client.post("/node/$t/notes?notes=https://ex.com")
        assertEquals("https://ex.com", read.node(t)!!.notes)
    }

    @Test
    fun depthGuardRejectsFifthLevel() = app { client ->
        val a = commands.createProject("a")
        val b = commands.createTask("b", a)
        val c = commands.createTask("c", b)
        val d = commands.createTask("d", c)
        val loose = commands.createTask("loose")
        // Under d (depth 4) → 409, no move.
        assertEquals(HttpStatusCode.Conflict, client.post("/node/$loose/move?parent=$d").status)
        assertEquals(null, read.node(loose)!!.parent)
        // Under b (depth 2) → OK.
        assertEquals(HttpStatusCode.OK, client.post("/node/$loose/move?parent=$b").status)
        assertEquals(b, read.node(loose)!!.parent)
    }

    @Test
    fun inboxRendersTriagePanelWiring() = app { client ->
        commands.createTask("triage me", inbox)
        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("class=\"triage\""), "triage panel missing: $home")
        assertTrue(home.contains("data-signals-exp"))
        assertTrue(home.contains("size-chips"))
        assertTrue(home.contains("No file suggestions yet")) // wired stub slot
    }
}
