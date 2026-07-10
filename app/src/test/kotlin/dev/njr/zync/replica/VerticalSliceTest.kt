package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.sync.BlobKeyResponse
import dev.njr.zync.core.sync.PullResponse
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.core.sync.PushResponse
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * M5 acceptance — the vertical slice end to end: **pair the phone → capture offline →
 * reconnect → the ops appear on the server**. Composes pairing, the op-log write path,
 * capture, blobs, and signed sync against a fake server backed by the **real core
 * merge** that verifies the paired device's signatures on every request.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
class VerticalSliceTest {
    private class MutableClock(var ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    private val json = Json
    private val validCode = "PAIR42"
    private val serverSeed = ByteArray(32) { (it + 11).toByte() }
    private val serverSigner = Ed25519DeviceSigner("server", serverSeed)
    private val serverPub = Ed25519DeviceSigner.publicKeyOf(serverSeed)

    private fun fingerprint(pub: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(pub).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(16)

    /** The whole server surface (pair, sync push/pull, blob) over MockEngine, real merge. */
    private inner class FakeZyncServer {
        val store = InMemoryStateStore()
        val log = mutableListOf<Op>()
        val seen = mutableSetOf<String>()
        var head = 0L
        val devices = mutableMapOf<String, ByteArray>() // deviceId -> pubkey
        val blobs = mutableMapOf<String, ByteArray>()

        private fun verified(method: String, path: String, h: (String) -> String?): Boolean {
            val pub = devices[h("X-Device-Id")] ?: return false
            val canonical = "$method\n$path\n${h("X-Timestamp")}\n${h("X-Nonce")}"
            return Ed25519DeviceSigner.verify(pub, canonical.encodeToByteArray(), Base64.decode(h("X-Signature")!!))
        }

        private fun Op.withSeq(seq: Long): Op = when (this) {
            is Op.SetField -> copy(seq = seq)
            is Op.Move -> copy(seq = seq)
            is Op.AddTag -> copy(seq = seq)
            is Op.RemoveTag -> copy(seq = seq)
            is Op.AddAttachment -> copy(seq = seq)
            is Op.Tombstone -> copy(seq = seq)
        }

        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method.value
            when {
                method == "POST" && path == "/pair" -> {
                    val req = json.decodeFromString(PairRequest.serializer(), request.body.toByteArray().decodeToString())
                    if (req.code != validCode) respond("bad code", HttpStatusCode.Unauthorized)
                    else {
                        val pub = Base64.decode(req.devicePublicKey)
                        val deviceId = fingerprint(pub)
                        devices[deviceId] = pub
                        val confirmation = serverSigner.sign(pairingConfirmationMessage(deviceId, req.devicePublicKey))
                        respond(json.encodeToString(PairResponse.serializer(), PairResponse(deviceId, Base64.encode(serverPub), Base64.encode(confirmation))), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                }
                !verified(method, path, request.headers::get) -> respond("unauthorized", HttpStatusCode.Unauthorized)
                method == "POST" && path == "/sync/push" -> {
                    val req = json.decodeFromString(PushRequest.serializer(), request.body.toByteArray().decodeToString())
                    for (op in req.ops) if (seen.add(op.opId.toString())) { head += 1; op.withSeq(head).let { apply(it, store); log += it } }
                    respond(json.encodeToString(PushResponse.serializer(), PushResponse(req.ops.map { it.opId }, head)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                method == "GET" && path == "/sync/pull" -> {
                    val since = request.url.parameters["since"]!!.toLong()
                    respond(json.encodeToString(PullResponse.serializer(), PullResponse(log.filter { (it.seq ?: 0) > since }, head)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                method == "POST" && path == "/blob" -> {
                    val bytes = request.body.toByteArray()
                    val key = blobKeyOf(bytes)
                    blobs[key] = bytes
                    respond(json.encodeToString(BlobKeyResponse.serializer(), BlobKeyResponse(key)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        })
    }

    @Test
    fun pairCaptureOfflineThenSyncLandsOnServer() = runBlocking {
        val server = FakeZyncServer()

        // 1. pair the phone
        val deviceSeed = Ed25519DeviceSigner.generateSeed()
        val invite = PairingInvite("https://srv", serverPub, validCode, expiresAt = Long.MAX_VALUE)
        val paired = (PairingClient(server.http).pair(invite, deviceSeed) as PairingOutcome.Paired).server

        // 2. build the phone replica
        val db = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)
        val store = SqlDelightStateStore(db)
        val clock = MutableClock(1_000)
        val writer = OpWriter(db, store, LocalHlc(FakeHlcStore(), paired.deviceId, clock), paired.deviceId, clock, Random(1))
        val localBlobs = LocalBlobStore(java.io.File(System.getProperty("java.io.tmpdir"), "vs-blobs-${clock.ms}"))
        val capture = ReplicaCapture(writer, localBlobs, inbox = { null })
        val signer = Ed25519DeviceSigner(paired.deviceId, deviceSeed)

        // 3. capture offline (no network)
        val photo = "receipt-bytes".encodeToByteArray()
        capture.captureAttachment("Receipt", photo, "image", "receipt.jpg")
        capture.captureNote("Call mom")
        assertTrue(db.transportQueries.selectUnsynced().executeAsList().isNotEmpty())

        // 4. reconnect: upload the blob, then sync ops
        val nonces = generateSequence(0) { it + 1 }.iterator()
        val uploader = BlobUploader(server.http, paired.address, localBlobs, signer, now = { clock.ms }, nonce = { "u${nonces.next()}" })
        assertTrue(uploader.upload(blobKeyOf(photo)))
        SyncClient(server.http, paired.address, db, store, LocalHlc(FakeHlcStore(), paired.deviceId, clock), signer, now = { clock.ms }, nonce = { "s${nonces.next()}" }).sync()

        // 5. the captured ops + blob are now on the server, and state converges
        assertTrue(db.transportQueries.selectUnsynced().executeAsList().isEmpty())
        assertEquals(store.project(), server.store.project())
        assertTrue(server.blobs.containsKey(blobKeyOf(photo)))
        assertTrue(server.store.project().values.any { it.fields["title"] == JsonPrimitive("Receipt") })
        assertTrue(server.store.project().values.any { it.fields["title"] == JsonPrimitive("Call mom") })
    }
}
