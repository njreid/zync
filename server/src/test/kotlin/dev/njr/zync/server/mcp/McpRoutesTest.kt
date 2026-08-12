package dev.njr.zync.server.mcp

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.api.EnvBotAuth
import dev.njr.zync.server.api.ExternalOpApi
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.web.content.ContentReadModel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stateless MCP endpoint (spec 2026-08-11): transport, tool discovery, and always-propose. */
class McpRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun run(block: suspend (io.ktor.client.HttpClient, SyncService) -> Unit) = testApplication {
        val service = SyncService(JvmZyncDatabase.inMemory())
        val blobs = dev.njr.zync.server.blob.BlobService(dev.njr.zync.server.blob.InMemoryBlobStore())
        val api = ExternalOpApi(service, blobs = blobs)
        val server = McpServer(ContentReadModel(service.stateStore), api)
        application {
            install(ContentNegotiation) { json() }
            routing { mcpRoutes(server, EnvBotAuth("secret", "newz")) }
        }
        block(client, service)
    }

    private suspend fun io.ktor.client.HttpClient.rpc(
        method: String,
        params: JsonObject? = null,
        id: Int? = 1,
        bearer: String? = "secret",
    ): HttpResponse = post("/mcp") {
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("method", method)
            params?.let { put("params", it) }
        }.toString())
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private fun callParams(name: String, args: JsonObject) = buildJsonObject {
        put("name", name); put("arguments", args)
    }

    @Test
    fun initializeIsStatelessAndAdvertisesCapabilities() = run { client, _ ->
        val resp = client.rpc("initialize")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertNull(resp.headers["Mcp-Session-Id"], "stateless: no session id issued")
        val result = resp.obj()["result"]!!.jsonObject
        assertTrue(result["protocolVersion"] != null)
        assertTrue(result["capabilities"]!!.jsonObject["tools"] != null)
    }

    @Test
    fun getIsMethodNotAllowed() = run { client, _ ->
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp").status)
    }

    @Test
    fun toolsListIncludesReadAndWriteTools() = run { client, _ ->
        val tools = client.rpc("tools/list").obj()["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("search" in names && "get_node" in names)
        assertTrue("create_task" in names && "set_field" in names && "move" in names)
    }

    @Test
    fun unknownMethodIsMethodNotFound() = run { client, _ ->
        val err = client.rpc("does/notexist").obj()["error"]!!.jsonObject
        assertEquals(-32601, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun missingBearerIsUnauthorized() = run { client, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.rpc("initialize", bearer = null).status)
    }

    @Test
    fun createTaskViaMcpProposesRatherThanCommits() = run { client, service ->
        val result = client.rpc("tools/call", callParams("create_task", buildJsonObject { put("title", "From MCP") }))
            .obj()["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("proposed", result["structuredContent"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        // The created node carries the proposed flag (awaiting human review), not live content.
        assertTrue(service.stateStore.project().values.any {
            (it.fields["title"] as? JsonPrimitive)?.content == "From MCP" &&
                (it.fields["proposed"] as? JsonPrimitive)?.content == "true"
        })
    }

    @Test
    fun idempotencyKeyDoesNotCollideAcrossDifferentCallsSharingAJsonRpcId() = run { client, service ->
        // Regression: the idempotency key must include the tool + args, not just (bot, jsonrpc
        // id) — otherwise two unrelated write calls reusing the same id (very plausible; ids are
        // per-request, and this test's own `id = 1` default shows how easy that is) collapse into
        // one, silently dropping the second mutation while reporting success.
        val created = client.rpc("tools/call", callParams("create_task", buildJsonObject { put("title", "First") }), id = 1)
            .obj()["result"]!!.jsonObject
        val secondNode = client.rpc("tools/call", callParams("create_task", buildJsonObject { put("title", "Second") }), id = 1)
            .obj()["result"]!!.jsonObject
        assertEquals(false, created["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, secondNode["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(service.stateStore.project().values.any { (it.fields["title"] as? JsonPrimitive)?.content == "First" })
        assertTrue(service.stateStore.project().values.any { (it.fields["title"] as? JsonPrimitive)?.content == "Second" })
    }

    @Test
    fun retriedIdempotencyKeyReusesTheSameProposalRatherThanDoubleSubmitting() = run { client, service ->
        // Same (bot, id, tool, args) called twice — a genuine retry — must return the cached
        // result and mint exactly one proposal, not two.
        val args = buildJsonObject { put("title", "Retried task") }
        client.rpc("tools/call", callParams("create_task", args), id = 7)
        client.rpc("tools/call", callParams("create_task", args), id = 7)
        val matches = service.stateStore.project().values.count { (it.fields["title"] as? JsonPrimitive)?.content == "Retried task" }
        assertEquals(1, matches)
    }

    @Test
    fun commitCapableBotStillOnlyProposesThroughMcp() = run { client, service ->
        // The env bot is commit-capable (default caps). A live human title exists.
        val node = id(1)
        service.ingestLocal(Op.SetField(id(9), node, EntityType.Node, Hlc(5, 0, "server"), Actor.Human, "server", 5, "title", str("human")))
        val result = client.rpc("tools/call", callParams("set_field", buildJsonObject {
            put("id", node.toString()); put("field", "dueDate"); put("value", 123)
        })).obj()["result"]!!.jsonObject
        assertEquals("proposed", result["structuredContent"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        // The field is NOT written live — a suggestion node targets it instead.
        assertNull(service.stateStore.getRegister(RegisterKey(node, "dueDate")))
        assertTrue(service.stateStore.project().values.any {
            (it.fields["kind"] as? JsonPrimitive)?.content == "suggestion" &&
                (it.fields["targetId"] as? JsonPrimitive)?.content == node.toString()
        })
    }

    @Test
    fun searchToolReturnsMatchingNodes() = run { client, _ ->
        client.rpc("tools/call", callParams("create_task", buildJsonObject { put("title", "Buy oat milk") }))
        val result = client.rpc("tools/call", callParams("search", buildJsonObject { put("query", "oat") }))
            .obj()["result"]!!.jsonObject
        val nodes = result["structuredContent"]!!.jsonObject["nodes"]!!.jsonArray
        assertTrue(nodes.any { it.jsonObject["title"]?.jsonPrimitive?.content == "Buy oat milk" })
    }

    @Test
    fun writeToolsShareTheApiOpsRateBudget() = testApplication {
        // Regression: /mcp write tools must consume the SAME per-verb budget as /api/ops (spec
        // §8) — a bot can't dodge its rate limit by switching doors.
        val service = SyncService(JvmZyncDatabase.inMemory())
        val api = ExternalOpApi(service)
        val limiter = dev.njr.zync.server.api.VerbRateLimiter()
        val caps = dev.njr.zync.core.api.BotCapabilities(rateLimit = mapOf("create" to 1))
        val auth = dev.njr.zync.server.api.BotAuth { token -> if (token == "secret") dev.njr.zync.server.api.BotIdentity("bot", caps) else null }
        val server = McpServer(ContentReadModel(service.stateStore), api, rateLimiter = limiter)
        application {
            install(ContentNegotiation) { json() }
            routing { mcpRoutes(server, auth) }
        }
        suspend fun createTask(rpcId: Int) = client.rpc(
            "tools/call", callParams("create_task", buildJsonObject { put("title", "task $rpcId") }), id = rpcId,
        ).obj()["result"]!!.jsonObject

        val first = createTask(1)
        assertEquals(false, first["isError"]!!.jsonPrimitive.content.toBoolean())

        val second = createTask(2)
        assertEquals(true, second["isError"]!!.jsonPrimitive.content.toBoolean())
    }
}
