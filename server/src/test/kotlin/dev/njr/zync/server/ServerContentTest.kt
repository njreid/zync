package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerContentTest {
    @Test
    fun serverRendersUiAndBrowserMutationConverges() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val service = SyncService(db)
        val content = ServerContent(service)
        application { zyncModule(service, content = content, allowUnauthenticatedWeb = true) }

        // the assembled server serves the shared UI
        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("""src="/assets/datastar.js""""))
        assertTrue(home.contains("<h2>Inbox</h2>"))

        // a browser mutation is ingested as a server-authored op → merges + logs
        val created = client.post("/inbox?title=Write%20spec")
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue(service.state().values.any { it.fields["title"] == JsonPrimitive("Write spec") })

        // the op is in the transport log with a seq — it will sync to replicas
        val pulled = service.pull(since = 0, limit = 100).ops
        assertTrue(pulled.any { it.seq != null }, "server-authored op should be sequenced for sync")

        // and it renders back in the inbox
        assertTrue(client.get("/").bodyAsText().contains("Write spec"))
    }

    @Test
    fun refusesToServeWebWithoutBrowserAuthUnlessExplicitlyAllowed() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val service = SyncService(db)
        val content = ServerContent(service)
        application { zyncModule(service, content = content) }

        // Startup must fail closed: content + no webauthn + no explicit dev opt-in.
        val result = runCatching { client.get("/") }
        assertTrue(result.isFailure, "expected startup to fail without ZYNC_ALLOW_UNAUTHENTICATED_WEB")
    }
}
