package dev.njr.zync.server

import dev.njr.zync.data.NodeKind
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContextsApiTest {

    @Test
    fun `create context tag task and filter recursively`() = zyncTestApplication { _, _, client ->
        val ctx: ContextDto = client.post("/api/contexts") {
            contentType(ContentType.Application.Json); setBody(NameBody("groceries"))
        }.body()
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("buy milk"))
        }.body()
        val tagged: List<ContextDto> = client.put("/api/nodes/${task.id}/contexts") {
            contentType(ContentType.Application.Json); setBody(ContextIdsBody(listOf(ctx.id)))
        }.body()
        assertEquals(listOf("groceries"), tagged.map { it.name })
        val inContext: List<NodeDto> = client.get("/api/contexts/${ctx.id}/tasks").body()
        assertEquals(listOf("buy milk"), inContext.map { it.title })
    }

    @Test
    fun `destinations lists folders then projects`() = zyncTestApplication { _, _, client ->
        val folder: NodeDto = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNodeBody(NodeKind.FOLDER, null, "Work"))
        }.body()
        client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNodeBody(NodeKind.PROJECT, folder.id, "Site"))
        }
        val dest: List<NodeDto> = client.get("/api/destinations").body()
        assertEquals(listOf("Inbox", "Someday", "Work", "Site"), dest.map { it.title })
    }

    @Test
    fun `websocket pushes changed event on mutation`() = zyncTestApplication { _, _, client ->
        val ws = createClient {
            install(WebSockets)
        }
        ws.webSocket("/api/events?token=test-token") {
            val hello = (incoming.receive() as Frame.Text).readText()
            assertTrue(hello.contains("hello"))
            client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("trigger"))
            }
            withTimeout(5_000) {
                val evt = (incoming.receive() as Frame.Text).readText()
                assertTrue(evt.contains("changed"))
            }
        }
    }

    @Test
    fun `tasks of unknown context is 404`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/contexts/9999/tasks").status)
    }
}
