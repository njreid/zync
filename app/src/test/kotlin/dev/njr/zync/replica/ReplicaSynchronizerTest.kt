package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/**
 * The production sync pass ([ReplicaSynchronizer]) against a MockEngine server:
 * attachment bytes must reach `/blob` BEFORE the referencing op reaches `/sync/push`,
 * and a failed blob upload must abort the pass with the ops still unsynced.
 */
@RunWith(RobolectricTestRunner::class)
class ReplicaSynchronizerTest {
    private class FixedClock(private val ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    private val json = Json
    private val signer = Ed25519DeviceSigner("phone-device", ByteArray(32) { (it + 1).toByte() })

    private fun harness(blobStatus: HttpStatusCode = HttpStatusCode.OK): Triple<ReplicaSynchronizer, MutableList<String>, dev.njr.zync.data.db.ZyncDatabase> {
        val db = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)
        val store = SqlDelightStateStore(db)
        val clock = FixedClock(1000)
        val hlc = LocalHlc(FakeHlcStore(), "phone", clock)
        val writer = OpWriter(db, store, hlc, "phone", clock, Random(7))
        val blobs = LocalBlobStore(File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "blobs-${Random.nextInt()}"))
        val capture = ReplicaCapture(writer, blobs, inbox = { null })
        capture.captureAttachment("Scan", byteArrayOf(1, 2, 3, 4), "pdf", "capture.pdf")

        val events = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/blob" -> {
                    if (blobStatus != HttpStatusCode.OK) return@MockEngine respond("nope", blobStatus)
                    val key = blobKeyOf(request.body.toByteArray())
                    events += "blob:$key"
                    respond("""{"key":"$key"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                "/sync/push" -> {
                    val req = json.decodeFromString(PushRequest.serializer(), request.body.toByteArray().decodeToString())
                    events += "push:${req.ops.size}"
                    respond(
                        json.encodeToString(PushResponse.serializer(), PushResponse(req.ops.map { it.opId }, req.ops.size.toLong())),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                }
                "/sync/pull" -> respond(
                    json.encodeToString(PullResponse.serializer(), PullResponse(emptyList(), 0)),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(engine)
        val now = { clock.nowMillis() }
        val nonce = { "n-${Random.nextInt()}" }
        val synchronizer = ReplicaSynchronizer(
            client = SyncClient(http, "https://srv", db, store, hlc, signer, now, nonce),
            blobs = BlobUploader(http, "https://srv", blobs, signer, now, nonce),
            db = db,
        )
        return Triple(synchronizer, events, db)
    }

    @Test
    fun uploadsBlobsBeforePushingTheirOps() = runBlocking {
        val (synchronizer, events, db) = harness()
        synchronizer.syncOnce()

        assertTrue("expected a blob upload, got $events", events.any { it.startsWith("blob:") })
        assertTrue("expected a push, got $events", events.any { it.startsWith("push:") })
        assertTrue(
            "blob must be uploaded before the ops that reference it: $events",
            events.indexOfFirst { it.startsWith("blob:") } < events.indexOfFirst { it.startsWith("push:") },
        )
        assertTrue("all ops acked", db.transportQueries.selectUnsynced().executeAsList().isEmpty())
    }

    @Test
    fun failedBlobUploadAbortsThePassLeavingOpsUnsynced() = runBlocking {
        val (synchronizer, events, db) = harness(blobStatus = HttpStatusCode.InternalServerError)
        val result = runCatching { synchronizer.syncOnce() }

        assertTrue("expected the pass to fail", result.exceptionOrNull() is SyncRequestException)
        assertTrue("no ops may be pushed after a blob failure: $events", events.none { it.startsWith("push:") })
        assertTrue("ops stay queued for retry", db.transportQueries.selectUnsynced().executeAsList().isNotEmpty())
    }
}
