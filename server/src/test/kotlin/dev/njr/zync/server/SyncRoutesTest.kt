package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.sync.BootstrapSnapshot
import dev.njr.zync.server.sync.PullResponse
import dev.njr.zync.server.sync.PushRequest
import dev.njr.zync.server.sync.PushResponse
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncRoutesTest {
    private val json = Json

    @Test
    fun pushPullBootstrapHealthOverHttp() = testApplication {
        val service = SyncService(JvmZyncDatabase.inMemory())
        application { zyncModule(service) }

        val ops = Ops()
        val t = id(1)
        val request = PushRequest(listOf(ops.setField(t, "title", str("Buy milk"), hlc(10))))

        val pushBody = client.post("/sync/push") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(PushRequest.serializer(), request))
        }.bodyAsText()
        val push = json.decodeFromString(PushResponse.serializer(), pushBody)
        assertEquals(request.ops.map { it.opId }, push.ackedOpIds)
        assertEquals(1L, push.serverHead)

        val pull = json.decodeFromString(PullResponse.serializer(), client.get("/sync/pull?since=0").bodyAsText())
        assertEquals(1, pull.ops.size)
        assertEquals(1L, pull.ops.single().seq)

        val boot = json.decodeFromString(BootstrapSnapshot.serializer(), client.get("/sync/bootstrap").bodyAsText())
        assertTrue(boot.registers.any { it.entityId == t })

        assertEquals("ok", client.get("/health").bodyAsText())
    }
}
