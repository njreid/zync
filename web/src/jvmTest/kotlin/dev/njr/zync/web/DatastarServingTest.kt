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
import kotlin.test.assertTrue

class DatastarServingTest {
    @Test
    fun servesRuntimeAndLayoutWiresDatastar() = testApplication {
        val store = InMemoryStateStore()
        val commands = ContentCommands(RecordingEmitter(store))
        val inbox = commands.createProject("Inbox")
        val read = ContentReadModel(store)

        application {
            install(SSE)
            routing { webRoutes(read, inbox = { inbox }, changes = ChangeNotifier()) }
        }

        // the vendored runtime is served locally (offline-safe)
        assertTrue(client.get("/assets/datastar.js").bodyAsText().contains("Datastar v1.0.2"))

        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("""src="/assets/datastar.js""""), "datastar script tag missing: $home")
        assertTrue(home.contains("""data-on:load="@get('/updates')""""), "SSE hook missing")
        assertTrue(home.contains("""id="inbox""""))
    }

    @Test
    fun renderFragmentWrapsInIdDiv() {
        val html = WebPlatform.renderFragment("inbox") {}
        assertTrue(html.startsWith("""<div id="inbox""""), "fragment: $html")
    }
}
