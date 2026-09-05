package dev.njr.zync.server.api

import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.NodeDto
import dev.njr.zync.core.api.NodeListDto
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.server.testServerHlc
import dev.njr.zync.web.content.ContentReadModel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The external-op-api read side (spec §6): GET /api/items/{id} and GET /api/search. */
class ApiReadTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun run(block: suspend (io.ktor.client.HttpClient, SyncService) -> Unit) = testApplication {
        val service = SyncService(JvmZyncDatabase.inMemory())
        val blobs = dev.njr.zync.server.blob.BlobService(dev.njr.zync.server.blob.InMemoryBlobStore())
        val api = ExternalOpApi(service, testServerHlc(), blobs = blobs)
        val read = ContentReadModel(service.stateStore)
        application {
            install(ContentNegotiation) { json() }
            routing { apiRoutes(api, EnvBotAuth("secret", "newz"), blobs, read = read) }
        }
        block(client, service)
    }

    private suspend fun io.ktor.client.HttpClient.create(title: String, notes: String? = null): String {
        val resp = post("/api/ops") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpEnvelope.serializer(), OpEnvelope(intents = listOf(
                OpIntent(op = "create", title = title, fields = notes?.let { mapOf("notes" to kotlinx.serialization.json.JsonPrimitive(it)) }),
            ))))
        }
        return json.decodeFromString(EnvelopeResult.serializer(), resp.bodyAsText()).results.single().nodeId!!
    }

    @Test
    fun getItemReturnsTheProjectedNode() = run { client, _ ->
        val id = client.create("Buy oat milk", notes = "from Sam")
        val resp = client.get("/api/items/$id") { header(HttpHeaders.Authorization, "Bearer secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val node = json.decodeFromString(NodeDto.serializer(), resp.bodyAsText())
        assertEquals(id, node.id)
        assertEquals("Buy oat milk", node.title)
        assertEquals("from Sam", node.notes)
    }

    @Test
    fun searchFindsByKeyword() = run { client, _ ->
        client.create("Buy oat milk")
        client.create("Read the KMP spec")
        val resp = client.get("/api/search?q=oat") { header(HttpHeaders.Authorization, "Bearer secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val hits = json.decodeFromString(NodeListDto.serializer(), resp.bodyAsText()).nodes
        assertTrue(hits.any { it.title == "Buy oat milk" })
        assertTrue(hits.none { it.title == "Read the KMP spec" })
    }

    @Test
    fun getUnknownItemIsNotFound() = run { client, _ ->
        val resp = client.get("/api/items/${dev.njr.zync.core.id.Ulid.parse("00000000000000000000000000")}") {
            header(HttpHeaders.Authorization, "Bearer secret")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun readRequiresABearerToken() = run { client, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/search?q=x").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/items/x").status)
    }
}
