package dev.njr.zync.sdk

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Smoke tests against an in-process stdlib HTTP stub (no dependency on a running Zync server). */
class ZyncClientTest {
    private fun withStub(status: Int, response: String, handle: (String?, String) -> Unit = { _, _ -> }, block: (ZyncClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readBytes().decodeToString()
            handle(auth, body)
            val bytes = response.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(ZyncClient("http://127.0.0.1:${server.address.port}", "secret"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun createSendsAuthedEnvelopeAndParsesResult() {
        var seenAuth: String? = null
        var seenBody = ""
        withStub(
            200,
            """{"results":[{"op":"create","nodeId":"01JZ","status":"committed"}]}""",
            handle = { auth, body -> seenAuth = auth; seenBody = body },
        ) { client ->
            val r = client.create("Hello from a bot", parent = "inbox")
            assertEquals("committed", r.status)
            assertEquals("01JZ", r.nodeId)
        }
        assertEquals("Bearer secret", seenAuth)
        assertTrue(seenBody.contains("\"op\":\"create\"") && seenBody.contains("Hello from a bot"))
        assertTrue(seenBody.contains("idempotencyKey"), "an idempotency key is auto-generated")
    }

    @Test
    fun proposeSetsMode() {
        var seenBody = ""
        withStub(200, """{"results":[{"op":"setField","nodeId":"01S","status":"proposed"}]}""", handle = { _, b -> seenBody = b }) { client ->
            val r = client.propose("01T", "dueDate", kotlinx.serialization.json.JsonPrimitive(123L))
            assertEquals("proposed", r.status)
        }
        assertTrue(seenBody.contains("\"mode\":\"propose\""))
    }

    @Test
    fun uploadBlobReturnsTheKey() {
        withStub(200, """{"key":"blob-abc"}""") { client ->
            assertEquals("blob-abc", client.uploadBlob("bytes".encodeToByteArray()))
        }
    }

    @Test
    fun nonSuccessThrows() {
        withStub(400, "intents must be 1..200") { client ->
            val ex = assertFailsWith<ZyncApiException> { client.create("x") }
            assertEquals(400, ex.status)
        }
    }
}
