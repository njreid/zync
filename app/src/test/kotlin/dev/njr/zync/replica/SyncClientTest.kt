package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.sync.PullResponse
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.core.sync.PushResponse
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import dev.njr.zync.data.db.ZyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * The phone sync client against a Ktor MockEngine "server" backed by the **real core
 * merge** — so push/pull is a genuine convergence test, and it verifies the device
 * signature on every request.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncClientTest {
    private class MutableClock(var ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    private val json = Json
    private val deviceSeed = ByteArray(32) { (it + 1).toByte() }
    private val signer = Ed25519DeviceSigner("phone-device", deviceSeed)
    private val devicePub = Ed25519DeviceSigner.publicKeyOf(deviceSeed)

    private fun db() = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)

    private fun verifySignature(method: String, path: String, headers: (String) -> String?): Boolean {
        val canonical = "$method\n$path\n${headers("X-Timestamp")}\n${headers("X-Nonce")}"
        val sig = Base64.decode(headers("X-Signature")!!)
        return Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(devicePub, 0))
            update(canonical.encodeToByteArray(), 0, canonical.encodeToByteArray().size)
        }.verifySignature(sig)
    }

    /** A fake server: real core merge + monotonic seq, served over MockEngine. */
    private inner class FakeServer {
        val store = InMemoryStateStore()
        val log = mutableListOf<Op>()
        var head = 0L
        val seenOps = mutableSetOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method.value
            assertTrue("request must be signed", verifySignature(method, path, request.headers::get))
            when {
                method == "POST" && path == "/sync/push" -> {
                    val req = json.decodeFromString(PushRequest.serializer(), request.body.toByteArray().decodeToString())
                    for (op in req.ops) {
                        if (seenOps.add(op.opId.toString())) {
                            head += 1
                            val withSeq = op.copyWithSeq(head)
                            apply(withSeq, store)
                            log += withSeq
                        }
                    }
                    respond(json.encodeToString(PushResponse.serializer(), PushResponse(req.ops.map { it.opId }, head)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                method == "GET" && path == "/sync/pull" -> {
                    val since = request.url.parameters["since"]!!.toLong()
                    val ops = log.filter { (it.seq ?: 0) > since }
                    respond(json.encodeToString(PullResponse.serializer(), PullResponse(ops, head)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(engine)
    }

    private fun Op.copyWithSeq(seq: Long): Op = when (this) {
        is Op.SetField -> copy(seq = seq)
        is Op.Move -> copy(seq = seq)
        is Op.AddTag -> copy(seq = seq)
        is Op.RemoveTag -> copy(seq = seq)
        is Op.AddAttachment -> copy(seq = seq)
        is Op.Tombstone -> copy(seq = seq)
    }

    private fun client(db: ZyncDatabase, store: SqlDelightStateStore, http: HttpClient, hlcStore: HlcStore, clock: Clock) =
        SyncClient(http, "https://srv", db, store, LocalHlc(hlcStore, "phone", clock), signer, now = { clock.nowMillis() }, nonce = { "n${clock.nowMillis()}-${Random.nextInt()}" })

    @Test
    fun pushMarksSyncedAndServerConverges() = runBlocking {
        val server = FakeServer()
        val phoneDb = db()
        val phoneStore = SqlDelightStateStore(phoneDb)
        val clock = MutableClock(1000)
        val writer = OpWriter(phoneDb, phoneStore, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(2))

        val project = writer.createNode("Buy milk")
        writer.setField(project, "status", JsonPrimitive("ACTIVE"))
        assertEquals(2, phoneDb.transportQueries.selectUnsynced().executeAsList().size)

        client(phoneDb, phoneStore, server.http, FakeHlcStore(), clock).push()

        assertTrue(phoneDb.transportQueries.selectUnsynced().executeAsList().isEmpty()) // all acked
        assertEquals(phoneStore.project(), server.store.project())                       // converged
    }

    @Test
    fun pullAppliesRemoteOpsAndAdvancesCursor() = runBlocking {
        val server = FakeServer()
        val clock = MutableClock(1000)

        // phone A creates + pushes
        val aDb = db(); val aStore = SqlDelightStateStore(aDb)
        val writer = OpWriter(aDb, aStore, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(3))
        writer.createNode("shared task")
        client(aDb, aStore, server.http, FakeHlcStore(), clock).push()

        // phone B pulls from empty and converges to the server
        val bDb = db(); val bStore = SqlDelightStateStore(bDb)
        client(bDb, bStore, server.http, FakeHlcStore(), clock).pull()

        assertEquals(server.store.project(), bStore.project())
        assertEquals(server.head, bDb.transportQueries.getCursor("server").executeAsOne())

        // second pull is a no-op (cursor at head)
        client(bDb, bStore, server.http, FakeHlcStore(), clock).pull()
        assertEquals(server.store.project(), bStore.project())
    }

    @Test
    fun rePushIsIdempotent() = runBlocking {
        val server = FakeServer()
        val clock = MutableClock(1000)
        val db = db(); val store = SqlDelightStateStore(db)
        val writer = OpWriter(db, store, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(4))
        writer.createNode("once")

        val c = client(db, store, server.http, FakeHlcStore(), clock)
        c.push()
        val headAfterFirst = server.head
        // second push has nothing unsynced left to send → no duplicate ingest
        c.push()
        assertEquals(headAfterFirst, server.head)
        assertTrue(db.transportQueries.selectUnsynced().executeAsList().isEmpty())
    }
}
