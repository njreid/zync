package dev.njr.zync.server.api

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.sdk.ZyncApiException
import dev.njr.zync.sdk.ZyncClient
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.InMemoryBlobStore
import dev.njr.zync.server.sync.SyncService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * True end-to-end: the real [ZyncClient] (java.net.http, from `:sdk:kotlin`) drives a real
 * Netty server over a real TCP socket — closing the SDK → server → op-log loop. The SDK's own
 * unit tests hit an in-process stub, so they'd stay green even if the wire contract drifted
 * from what [ExternalOpApi]/[apiRoutes] actually accept; this test is the one that wouldn't.
 */
class SdkE2ETest {
    @Test
    fun kotlinSdkRoundTripsThroughARealServer() {
        val service = SyncService(JvmZyncDatabase.inMemory())
        val blobs = BlobService(InMemoryBlobStore())
        val api = ExternalOpApi(service, blobs = blobs)
        val server = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation) { json() }
            routing { apiRoutes(api, EnvBotAuth("secret", "newz"), blobs) }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val zync = ZyncClient("http://127.0.0.1:$port", "secret")

            // create → committed; provenance is server-assigned as Actor.Bot.
            val created = zync.create(title = "e2e item", parent = "inbox")
            assertEquals("committed", created.status)
            val node = Ulid.parse(created.nodeId!!)
            val title = service.stateStore.getRegister(RegisterKey(node, "title"))!!
            assertEquals(JsonPrimitive("e2e item"), title.value)
            assertEquals(Actor.Bot("newz"), title.actor)

            // comment → a child node commits.
            assertEquals("committed", zync.comment(created.nodeId!!, "auto note").status)

            // propose → a suggestion; the live field stays unset.
            assertEquals("proposed", zync.propose(created.nodeId!!, "dueDate", JsonPrimitive(123L)).status)
            assertNull(service.stateStore.getRegister(RegisterKey(node, "dueDate")))

            // blob upload + attach round-trips content-addressed bytes.
            val key = zync.uploadBlob("scanned-bytes".toByteArray())
            assertEquals("committed", zync.attach(created.nodeId!!, key, type = "pdf", name = "scan.pdf").status)

            // a wrong token is rejected at the socket, surfaced as a typed 401.
            val ex = assertFailsWith<ZyncApiException> { ZyncClient("http://127.0.0.1:$port", "nope").create(title = "x") }
            assertEquals(401, ex.status)
        } finally {
            server.stop(0, 0)
        }
    }
}
