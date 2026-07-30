package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Installability contract (Chrome "Install"/"Save as app", iOS "Add to Home Screen"):
 * a manifest + icons are served and the page links them.
 */
class PwaManifestTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)
    private val inbox = commands.createProject("Inbox")

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { routing { webRoutes(read, inbox = { inbox }, commands = commands) } }
        block(client)
    }

    @Test
    fun manifestIsServedAndPageLinksIt() = app { client ->
        val manifest = client.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, manifest.status)
        val body = manifest.bodyAsText()
        assertTrue(body.contains("\"name\": \"zync\""))
        assertTrue(body.contains("\"display\": \"standalone\""))
        assertTrue(body.contains("/icons/icon-192.png"))
        assertTrue(body.contains("\"purpose\": \"maskable\""))

        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("rel=\"manifest\" href=\"/manifest.webmanifest\""))
        assertTrue(home.contains("name=\"theme-color\""))
        assertTrue(home.contains("rel=\"apple-touch-icon\""))
    }

    @Test
    fun iconsAreServedAsPng() = app { client ->
        for (name in listOf("icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png")) {
            val response = client.get("/icons/$name")
            assertEquals(HttpStatusCode.OK, response.status, "expected $name to be served")
            val bytes = response.bodyAsBytes()
            assertTrue(bytes.size > 8, "$name looks empty")
            // PNG magic bytes.
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
        }
    }

    @Test
    fun unknownIconNameIsRejected() = app { client ->
        val response = client.get("/icons/evil.png")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
