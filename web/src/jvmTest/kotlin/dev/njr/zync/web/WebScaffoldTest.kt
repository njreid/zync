package dev.njr.zync.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebScaffoldTest {
    @Test
    fun rendersShellAndHealth() = testApplication {
        application { routing { webRoutes() } }
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("<h1>zync</h1>"), "expected the shell heading: $html")
        assertTrue(html.contains("shared web module"))
        assertEquals("ok", client.get("/health").bodyAsText())
    }
}
