package dev.njr.zync.server

import dev.njr.zync.data.ZyncDatabase
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
