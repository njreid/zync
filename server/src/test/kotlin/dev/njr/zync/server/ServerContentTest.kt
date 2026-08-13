package dev.njr.zync.server

import dev.njr.zync.core.api.BotCapabilities
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.api.BotIdentity
import dev.njr.zync.server.api.ExternalOpApi
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
    /**
     * Regression test for the bug fixed alongside [ServerHlc]'s persistence: `Main.kt`
     * constructs exactly ONE [dev.njr.zync.server.clock.ServerHlc] and passes it to BOTH
     * [ServerContent] (browser-authored ops, via [dev.njr.zync.server.content.ServerOpEmitter])
     * and [ExternalOpApi] (bot-authored ops, via [dev.njr.zync.server.api.RecordingBotEmitter]).
     * If a future refactor silently reverted to two independent `ServerHlc` instances, each
     * would mint its own HLCs starting from the same wall-clock-derived baseline — ops from the
     * two paths could tie or land out of issuance order. This test shares ONE instance across
     * both emitters, exactly as production wiring does, interleaves browser and bot ops, and
     * asserts their HLCs come out in strictly increasing issuance order.
     */
    @Test
    fun browserAndBotEmittersShareOneHlcAndStayMutuallyOrdered() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val service = SyncService(db)
        val hlc = testServerHlc() // ONE shared ServerHlc — mirrors Main.kt's production wiring.
        val content = ServerContent(service, hlc)
        val api = ExternalOpApi(service, hlc)
        val bot = BotIdentity("regtest", BotCapabilities())

        val node = id(1)
        service.ingestLocal(
            Op.SetField(id(9), node, EntityType.Node, Hlc(5, 0, "seed"), Actor.Human, "seed", 5, "title", str("item")),
        )
        val since = service.pull(since = 0, limit = 100).head

        // Interleave: browser op, bot op, browser op, bot op — through the two real emitter paths.
        content.commands.setNotes(node, "browser edit 1")
        val bot1 = api.submit(bot, OpEnvelope(intents = listOf(
            OpIntent(op = "setField", target = node.toString(), field = "notes", value = JsonPrimitive("bot edit 1")),
        )))
        assertEquals("committed", bot1.results.single().status)
        content.commands.setNotes(node, "browser edit 2")
        val bot2 = api.submit(bot, OpEnvelope(intents = listOf(
            OpIntent(op = "setField", target = node.toString(), field = "notes", value = JsonPrimitive("bot edit 2")),
        )))
        assertEquals("committed", bot2.results.single().status)

        val ops = service.pull(since = since, limit = 100).ops
        assertEquals(4, ops.size, "expected exactly the 4 interleaved browser/bot ops")
        val hlcs = ops.map { it.hlc }
        for (i in 1 until hlcs.size) {
            assertTrue(
                hlcs[i] > hlcs[i - 1],
                "op $i HLC ${hlcs[i]} must be strictly after op ${i - 1} HLC ${hlcs[i - 1]} — " +
                    "browser and bot emitters must share one ServerHlc to stay mutually ordered",
            )
        }
    }

    @Test
    fun serverRendersUiAndBrowserMutationConverges() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val service = SyncService(db)
        val content = ServerContent(service, testServerHlc())
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
        val content = ServerContent(service, testServerHlc())
        application { zyncModule(service, content = content) }

        // Startup must fail closed: content + no webauthn + no explicit dev opt-in.
        val result = runCatching { client.get("/") }
        assertTrue(result.isFailure, "expected startup to fail without ZYNC_ALLOW_UNAUTHENTICATED_WEB")
    }
}
