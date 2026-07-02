package dev.njr.zync.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerAuthTest {

    @Test
    fun `request without token is rejected`() = zyncTestApplication { _, _ ->
        val bare = createClient { }   // no default token header
        assertEquals(HttpStatusCode.Unauthorized, bare.get("/index.html").status)
    }

    @Test
    fun `header token grants access to static assets`() = zyncTestApplication { _, client ->
        val res = client.get("/index.html")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("ok"))
    }

    @Test
    fun `query token sets cookie and cookie works afterwards`() = zyncTestApplication { _, _ ->
        val bare = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
        assertEquals(HttpStatusCode.OK, bare.get("/index.html?token=test-token").status)
        assertEquals(HttpStatusCode.OK, bare.get("/index.html").status) // cookie now carries auth
    }

    @Test
    fun `unknown path with valid token is 404`() = zyncTestApplication { _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/nope.js").status)
    }
}
