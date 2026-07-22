package dev.njr.zync.server.api

import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiOpsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun run(token: String? = "secret", block: suspend (io.ktor.client.HttpClient, SyncService) -> Unit) =
        testApplication {
            val service = SyncService(JvmZyncDatabase.inMemory())
            val api = ExternalOpApi(service)
            application {
                install(ContentNegotiation) { json() }
                routing { apiRoutes(api, EnvBotAuth(token, "newz")) }
            }
            block(client, service)
        }

    private suspend fun io.ktor.client.HttpClient.submit(env: OpEnvelope, bearer: String? = "secret") =
        post("/api/ops") {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpEnvelope.serializer(), env))
        }

    @Test
    fun createStampsBotProvenanceAndLands() = run { client, service ->
        val resp = client.submit(OpEnvelope(intents = listOf(OpIntent(op = "create", title = "From a bot"))))
        assertEquals(HttpStatusCode.OK, resp.status)
        val result = json.decodeFromString(EnvelopeResult.serializer(), resp.bodyAsText())
        val nodeId = dev.njr.zync.core.id.Ulid.parse(result.results.single().nodeId!!)
        assertEquals("committed", result.results.single().status)

        val title = service.stateStore.getRegister(RegisterKey(nodeId, "title"))!!
        assertEquals(JsonPrimitive("From a bot"), title.value)
        assertEquals(Actor.Bot("newz"), title.actor) // provenance is server-assigned
    }

    @Test
    fun commentAndSetFieldWork() = run { client, service ->
        val create = json.decodeFromString(
            EnvelopeResult.serializer(),
            client.submit(OpEnvelope(intents = listOf(OpIntent(op = "create", title = "T")))).bodyAsText(),
        ).results.single().nodeId!!
        val resp = client.submit(
            OpEnvelope(intents = listOf(
                OpIntent(op = "comment", target = create, text = "auto note"),
                OpIntent(op = "setField", target = create, field = "notes", value = JsonPrimitive("https://x")),
            )),
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        val node = dev.njr.zync.core.id.Ulid.parse(create)
        assertEquals(JsonPrimitive("https://x"), service.stateStore.getRegister(RegisterKey(node, "notes"))!!.value)
        // the comment is a child node
        assertTrue(service.stateStore.project().values.any {
            it.parent?.toString() == create && it.fields["title"]?.let { t -> (t as JsonPrimitive).content } == "auto note"
        })
    }

    @Test
    fun atomicEnvelopeWithABadIntentIngestsNothing() = run { client, service ->
        val before = service.stateStore.project().size
        val resp = client.submit(
            OpEnvelope(intents = listOf(
                OpIntent(op = "create", title = "would-be"),
                OpIntent(op = "comment", target = "not-a-ulid", text = "boom"), // invalid target
            )),
        )
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(before, service.stateStore.project().size, "nothing should have been ingested")
    }

    @Test
    fun idempotentRetryReturnsSameResultAndIngestsOnce() = run { client, service ->
        val env = OpEnvelope(idempotencyKey = "k1", intents = listOf(OpIntent(op = "create", title = "once")))
        val first = json.decodeFromString(EnvelopeResult.serializer(), client.submit(env).bodyAsText())
        val sizeAfterFirst = service.stateStore.project().size
        val second = json.decodeFromString(EnvelopeResult.serializer(), client.submit(env).bodyAsText())
        assertEquals(first.results.single().nodeId, second.results.single().nodeId)
        assertEquals(sizeAfterFirst, service.stateStore.project().size, "retry must not re-ingest")
    }

    @Test
    fun rejectsMissingOrWrongToken() = run { client, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.submit(OpEnvelope(intents = listOf(OpIntent(op = "create"))), bearer = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.submit(OpEnvelope(intents = listOf(OpIntent(op = "create"))), bearer = "wrong").status)
    }

    @Test
    fun proposeModeMakesASuggestionNotALiveEdit() = run { client, service ->
        val node = id(1)
        service.ingestLocal(Op.SetField(id(9), node, EntityType.Node, Hlc(5, 0, "server"), Actor.Human, "server", 5, "title", str("orig")))
        val resp = client.submit(
            OpEnvelope(mode = "propose", intents = listOf(
                OpIntent(op = "setField", target = node.toString(), field = "dueDate", value = JsonPrimitive(123L)),
            )),
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("proposed", json.decodeFromString(EnvelopeResult.serializer(), resp.bodyAsText()).results.single().status)
        // The target is NOT edited…
        assertNull(service.stateStore.getRegister(RegisterKey(node, "dueDate")))
        // …instead a suggestion node targeting it exists.
        assertTrue(service.stateStore.project().values.any {
            (it.fields["kind"] as? JsonPrimitive)?.content == "suggestion" &&
                (it.fields["targetId"] as? JsonPrimitive)?.content == node.toString()
        })
    }

    @Test
    fun botDoesNotOverwriteAHumanField() = run { client, service ->
        // A human writes the title directly (Actor.Human), then a bot tries to change it.
        val node = id(1)
        service.ingestLocal(Op.SetField(id(9), node, EntityType.Node, Hlc(5, 0, "server"), Actor.Human, "server", 5, "title", str("human title")))
        client.submit(OpEnvelope(intents = listOf(OpIntent(op = "setField", target = node.toString(), field = "title", value = JsonPrimitive("bot title")))))
        // Merge rule: the human value stands.
        assertEquals(JsonPrimitive("human title"), service.stateStore.getRegister(RegisterKey(node, "title"))!!.value)
    }
}
