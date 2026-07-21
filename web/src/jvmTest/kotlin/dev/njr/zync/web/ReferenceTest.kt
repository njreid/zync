package dev.njr.zync.web

import dev.njr.zync.core.content.Status
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reference tab + keyword search + File command (GTD triage §7, build S4). */
class ReferenceTest {
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
    fun referenceTabRendersSearchBoxAndFiledTree() = app { client ->
        val filed = commands.createTask("Archived receipt")
        commands.file(filed)
        val page = client.get("/reference").bodyAsText()
        assertTrue(page.contains("<h2>Reference</h2>"))
        assertTrue(page.contains("type=\"search\""))
        assertTrue(page.contains("data-key=\"r\""), "4th Reference tab present")
        assertTrue(page.contains("Archived receipt"), "filed tree lists the item: $page")
    }

    @Test
    fun searchPatchesResultsFragment() = app { client ->
        commands.createTask("Quarterly budget review")
        val body = client.get("/reference/search?q=budget").bodyAsText()
        assertTrue(body.contains("datastar-patch-elements"))
        assertTrue(body.contains("reference-results"))
        assertTrue(body.contains("Quarterly budget review"))
        // No matches → "No matches."
        assertTrue(client.get("/reference/search?q=zzznope").bodyAsText().contains("No matches."))
    }

    @Test
    fun fileCommandArchivesAndReparents() = app { client ->
        val t = commands.createTask("do taxes", inbox)
        client.post("/node/$t/file")
        val v = read.node(t)!!
        assertEquals(Status.FILED, v.status)
        assertEquals(WellKnownNodes.REFERENCE_ROOT, v.parent)
        // Left the active surfaces…
        assertFalse(read.inbox(inbox).any { it.id == t })
        assertFalse(read.activeTasks().any { it.id == t })
        // …but appears in Reference + search.
        assertTrue(read.reference().any { it.id == t })
        assertTrue(read.search("taxes").any { it.id == t })
    }
}
