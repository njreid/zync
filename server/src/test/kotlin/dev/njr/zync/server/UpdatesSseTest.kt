package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.web.webRoutes
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpdatesSseTest {
    private suspend fun readEvent(ch: ByteReadChannel): String {
        val sb = StringBuilder()
        while (true) {
            val line = ch.readUTF8Line() ?: break
            if (line.isEmpty()) { if (sb.isNotEmpty()) break else continue }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    @Test
    fun updatesPushesInboxOnChange() = testApplication {
        val service = SyncService(JvmZyncDatabase.inMemory())
        val content = ServerContent(service)
        content.commands.createTask("Seed item")
        application {
            install(SSE)
            routing { webRoutes(content.read, changes = content.changes, commands = content.commands) }
        }
        client.prepareGet("/updates").execute { resp ->
            val ch = resp.bodyAsChannel()
            // No on-connect push (that would clobber a just-loaded surface); let the server-side
            // collector subscribe, then a mutation elsewhere (phone push / other browser) must push.
            kotlinx.coroutines.delay(300)
            content.commands.createTask("Pushed later")
            content.changes.notifyChanged()
            val frame = withTimeoutOrNull(3000) { readEvent(ch) }
            assertNotNull(frame, "expected a push after notifyChanged")
            assertTrue(frame.contains("Pushed later"), "the pushed frame should carry the new item")
        }
    }
}
