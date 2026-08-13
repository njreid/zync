package dev.njr.zync.server.operator

import dev.njr.zync.core.api.BotCapabilities
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.Ops
import dev.njr.zync.server.api.BotIdentity
import dev.njr.zync.server.api.ExternalOpApi
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import dev.njr.zync.server.sync.SettableIngestHook
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.server.testServerHlc
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the third unsynchronized-server-HLC bug: [OperatorRuntime] used to
 * construct its own `HlcGenerator("server", clock)`, independent of the ONE [dev.njr.zync.server.clock.ServerHlc]
 * that [dev.njr.zync.server.content.ServerOpEmitter] (browser) and [dev.njr.zync.server.api.RecordingBotEmitter]
 * (bot) already share (see [dev.njr.zync.server.ServerContentTest]). A third clock claiming the same
 * device id `"server"` could mint colliding/non-monotonic HLC tuples relative to those two. This test wires
 * all THREE op-emitting paths — browser, bot, and operator — onto ONE shared [dev.njr.zync.server.clock.ServerHlc],
 * exactly as `Main.kt` does, interleaves ops from all three, and asserts their HLCs come out in strictly
 * increasing issuance order.
 */
class OperatorRuntimeSharesHlcTest {
    @Test
    fun browserBotAndOperatorEmittersShareOneHlcAndStayMutuallyOrdered() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val hook = SettableIngestHook()
        val service = SyncService(db, hook = hook)
        val hlc = testServerHlc() // ONE shared ServerHlc — mirrors Main.kt's production wiring.
        val content = ServerContent(service, hlc)
        val api = ExternalOpApi(service, hlc)
        val runtime = OperatorRuntime(
            db = db,
            store = service.stateStore,
            operators = listOf(manifest("summarize")),
            scopes = ReadScopeResolver.default(),
            llm = FakeLlmClient(),
            emit = service::ingestLocal,
            hlc = hlc,
            executor = Executor { it.run() }, // synchronous, so assertions can see the operator's op
        )
        hook.delegate = runtime
        val bot = BotIdentity("regtest", BotCapabilities())
        val ops = Ops()
        val node = id(1)
        val since = service.pull(since = 0, limit = 100).head

        // Browser op, then a captured inbox task (triggers the "summarize" operator scope),
        // then a bot op — interleaving all three op-emitting paths through their real wiring.
        content.commands.setNotes(node, "browser edit 1")
        service.push(
            PushRequest(
                listOf(
                    ops.setField(node, "title", str("Buy milk"), hlc(1_000, 1)),
                    ops.setField(node, "status", str("ACTIVE"), hlc(1_000, 2)),
                ),
            ),
        )
        val bot1 = api.submit(
            bot,
            OpEnvelope(intents = listOf(OpIntent(op = "setField", target = node.toString(), field = "notes", value = JsonPrimitive("bot edit 1")))),
        )
        assertEquals("committed", bot1.results.single().status)

        val recorded = service.pull(since = since, limit = 100).ops
        val operatorOps = recorded.filter { it.actor is Actor.Operator }
        assertTrue(operatorOps.isNotEmpty(), "expected the summarize operator to have fired and emitted at least one op")

        // Only the three shared-clock emitters' ops (device id "server") are checked for mutual
        // ordering here — the "phone"-authored capture ops above use a fixed test timestamp to
        // deterministically land in the operator's scope and aren't part of the shared clock.
        val hlcs = recorded.filter { it.hlc.deviceId == "server" }.map { it.hlc }
        assertTrue(hlcs.size >= 3, "expected browser, bot, and operator ops, all device id \"server\": $hlcs")
        for (i in 1 until hlcs.size) {
            assertTrue(
                hlcs[i] > hlcs[i - 1],
                "op $i HLC ${hlcs[i]} must be strictly after op ${i - 1} HLC ${hlcs[i - 1]} — " +
                    "browser, bot, and operator emitters must share one ServerHlc to stay mutually ordered",
            )
        }
    }
}
