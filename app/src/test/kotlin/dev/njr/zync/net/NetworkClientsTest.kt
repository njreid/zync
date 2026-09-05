package dev.njr.zync.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkClientsTest {
    @Test
    fun `client sends CF Access headers when credentials are present`() = runTest {
        val engine = MockEngine { respondOk() }
        val credentials = requireNotNull(CfAccessCredentials.from(clientId = "id123", clientSecret = "secretXYZ"))
        val client = buildZyncHttpClient(credentials = credentials, engine = engine)

        client.get("https://zync.example.com/sync/pull")

        val sent = engine.requestHistory.single()
        assertEquals("id123", sent.headers["CF-Access-Client-Id"])
        assertEquals("secretXYZ", sent.headers["CF-Access-Client-Secret"])
    }

    @Test
    fun `client omits CF Access headers when credentials are blank`() = runTest {
        val engine = MockEngine { respondOk() }
        val client = buildZyncHttpClient(credentials = CfAccessCredentials.from("", "secret"), engine = engine)

        client.get("https://zync.example.com/sync/pull")

        val sent = engine.requestHistory.single()
        assertNull(sent.headers["CF-Access-Client-Id"])
        assertNull(sent.headers["CF-Access-Client-Secret"])
    }
}
