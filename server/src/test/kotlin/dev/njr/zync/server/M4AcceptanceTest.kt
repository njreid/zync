package dev.njr.zync.server

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.blob.BlobKeyResponse
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.InMemoryBlobStore
import dev.njr.zync.server.blob.blobKey
import dev.njr.zync.server.hardening.Hardening
import dev.njr.zync.server.hardening.TokenBucketRateLimiter
import dev.njr.zync.server.sync.BootstrapSnapshot
import dev.njr.zync.server.sync.PullResponse
import dev.njr.zync.server.sync.PushRequest
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M4 acceptance: the fully-assembled server (sync + blobs + hardening + debug)
 * merges conflict vectors correctly over the wire, round-trips a blob, converges a
 * fresh replica, and renders the debug view. (Auth enforcement + durability drill are
 * covered by AuthRoutesTest / DurabilityTest.)
 */
class M4AcceptanceTest {
    private val json = Json

    @Test
    fun assembledServerMergesVectorsRoundTripsBlobAndConverges() = testApplication {
        val service = SyncService(JvmZyncDatabase.inMemory())
        val blobs = BlobService(InMemoryBlobStore())
        val hardening = Hardening(TokenBucketRateLimiter(capacity = 10_000, refillPerSecond = 10_000.0))
        application { zyncModule(service, blobs = blobs, hardening = hardening) }

        val ops = Ops()
        val t = id(1)
        val a = id(2)
        val b = id(3)
        val ctx = id(4)
        val batch = listOf(
            // V1 LWW: later HLC wins
            ops.setField(t, "title", str("old"), hlc(10, dev = "phone")),
            ops.setField(t, "title", str("new"), hlc(12, dev = "desk")),
            // V7 tombstone terminal (edit is later but entity stays dead)
            ops.tombstone(a, hlc(10)),
            ops.setField(a, "title", str("edited"), hlc(20)),
            // V8 tag LWW: remove wins
            ops.addTag(t, ctx, hlc(10)),
            ops.removeTag(t, ctx, hlc(12)),
            // V3-style move cycle: b→under-t applied; t→under-b would cycle → skipped
            ops.move(b, t, hlc(11)),
            ops.move(t, b, hlc(12)),
        )

        // push over HTTP
        val pushStatus = client.post("/sync/push") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(PushRequest.serializer(), PushRequest(batch)))
        }.status
        assertEquals(HttpStatusCode.OK, pushStatus)

        // bootstrap reflects the merged state
        val snap = json.decodeFromString(BootstrapSnapshot.serializer(), client.get("/sync/bootstrap").bodyAsText())
        assertEquals(str("new"), snap.registers.first { it.entityId == t && it.field == "title" }.value)
        assertTrue(snap.tombstones.any { it.entityId == a })
        assertTrue(snap.tags.none { it.nodeId == t && it.contextId == ctx && it.present })
        assertTrue(snap.moves.isNotEmpty())
        assertEquals(t, service.state().getValue(b).parent) // b under t
        assertEquals(null, service.state().getValue(t).parent) // t stayed at root (cycle skip)

        // blob round-trip
        val payload = "acceptance".encodeToByteArray()
        val key = json.decodeFromString(BlobKeyResponse.serializer(), client.post("/blob") { setBody(payload) }.bodyAsText()).key
        assertEquals(blobKey(payload), key)
        assertEquals(HttpStatusCode.OK, client.get("/blob/$key").status)

        // a fresh replica that pulls all ops converges to the server's state
        val pulled = json.decodeFromString(PullResponse.serializer(), client.get("/sync/pull?since=0&limit=1000").bodyAsText()).ops
        val replica = InMemoryStateStore().apply { pulled.forEach { apply(it, this) } }
        assertEquals(service.state(), replica.project())

        // debug view renders and mentions an entity
        val debug = client.get("/debug")
        assertEquals(HttpStatusCode.OK, debug.status)
        assertTrue(debug.bodyAsText().contains(t.toString()))
    }
}
