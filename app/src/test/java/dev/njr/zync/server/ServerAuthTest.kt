package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerAuthTest {

    @Test
    fun `request without token is rejected`() = zyncTestApplication { _, _, _ ->
        val bare = createClient { }   // no default token header
        assertEquals(HttpStatusCode.Unauthorized, bare.get("/index.html").status)
    }

    @Test
    fun `unauthenticated POST to inbox is rejected and has no side effect`() =
        zyncTestApplication { _, _, client ->
            val bare = createClient { install(ContentNegotiation) { json() } }   // no default token header
            val res = bare.post("/api/inbox") {
                contentType(ContentType.Application.Json)
                setBody(TitleBody("sneaky task"))
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)

            val children: List<NodeDto> =
                client.get("/api/nodes/${ZyncDatabase.INBOX_ID}/children").body()
            assertTrue("Inbox must remain empty after rejected request", children.isEmpty())
        }

    @Test
    fun `header token grants access to static assets`() = zyncTestApplication { _, _, client ->
        val res = client.get("/index.html")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("ok"))
    }

    @Test
    fun `query token sets cookie and cookie works afterwards`() = zyncTestApplication { _, _, _ ->
        val bare = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
        assertEquals(HttpStatusCode.OK, bare.get("/index.html?token=test-token").status)
        assertEquals(HttpStatusCode.OK, bare.get("/index.html").status) // cookie now carries auth
    }

    @Test
    fun `unknown path with valid token is 404`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/nope.js").status)
    }

    @Test
    fun `unauthenticated websocket connect is rejected`() = zyncTestApplication { _, _, _ ->
        val bare = createClient { install(WebSockets) }
        var receivedHello = false
        try {
            bare.webSocket("/api/events") {
                val frame = incoming.receive()
                if (frame is Frame.Text && frame.readText().contains("hello")) {
                    receivedHello = true
                }
            }
            fail("expected the unauthenticated websocket upgrade to be rejected")
        } catch (_: Throwable) {
            // Expected: the server responds 401 to the upgrade request instead of
            // completing the handshake, so the client throws rather than connecting.
        }
        assertFalse("must not receive a hello frame without auth", receivedHello)
    }

    @Test
    fun `path traversal segment is rejected before reaching assets lambda`() {
        val db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val repo = NodeRepository(db)
        val traversalAttempts = mutableListOf<String>()
        try {
            testApplication {
                application {
                    zyncModule(db, repo, token = "test-token", assets = { path ->
                        traversalAttempts.add(path)
                        if (path == "index.html") "<html>ok</html>".toByteArray() to ContentType.Text.Html
                        else null
                    })
                }
                val client = createClient {
                    install(ContentNegotiation) { json() }
                    defaultRequest { headers.append(TOKEN_HEADER, "test-token") }
                }
                val res = client.get("/%2e%2e/secret")
                assertEquals(HttpStatusCode.NotFound, res.status)
            }
        } finally {
            db.close()
        }
        assertTrue(
            "assets lambda must never be invoked with a '..' segment",
            traversalAttempts.none { it.contains("..") },
        )
    }
}
