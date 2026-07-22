package dev.njr.zync.server.api

import dev.njr.zync.core.api.BlobKeyResult
import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.BlobTooLargeException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put

private const val MAX_INTENTS = 200

/** Resolve the bearer token to a bot, or null. */
private fun ApplicationCall.bot(auth: BotAuth): BotIdentity? {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    return token.takeIf { it.isNotEmpty() }?.let { auth.authenticate(it) }
}

/**
 * The external-op-api write door (spec §6): `POST /api/ops` takes a bearer-authed bot's
 * envelope and ingests it (idempotent per `(botId, idempotencyKey)`); `PUT /api/blobs`
 * content-addresses a blob to reference in an `attach` intent. Session-exempt (bearer).
 */
fun Route.apiRoutes(api: ExternalOpApi, auth: BotAuth, blobs: BlobService? = null) {
    val idem = IdempotencyCache()

    if (blobs != null) put("/api/blobs") {
        if (call.bot(auth) == null) return@put call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
        val bytes = call.receive<ByteArray>()
        val key = try {
            blobs.store(bytes)
        } catch (e: BlobTooLargeException) {
            return@put call.respondText("blob too large", status = HttpStatusCode.PayloadTooLarge)
        }
        call.respond(BlobKeyResult(key))
    }

    post("/api/ops") {
        val bot = call.bot(auth) ?: return@post call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)

        val env = try {
            call.receive<OpEnvelope>()
        } catch (e: Exception) {
            return@post call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        if (env.intents.isEmpty() || env.intents.size > MAX_INTENTS) {
            return@post call.respondText("intents must be 1..$MAX_INTENTS", status = HttpStatusCode.BadRequest)
        }

        env.idempotencyKey?.let { key -> idem.get(bot.id, key)?.let { return@post call.respond(it) } }
        val result = api.submit(bot, env)
        env.idempotencyKey?.let { idem.put(bot.id, it, result) }

        val status = if (result.results.any { it.status == "error" }) HttpStatusCode.BadRequest else HttpStatusCode.OK
        call.respond(status, result)
    }
}

/** Bounded `(botId, key) → result` LRU so a retried envelope returns the original result (spec §5). */
private class IdempotencyCache(private val max: Int = 1024) {
    private val map = object : LinkedHashMap<String, EnvelopeResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EnvelopeResult>): Boolean = size > max
    }

    @Synchronized fun get(botId: String, key: String): EnvelopeResult? = map["$botId::$key"]

    @Synchronized fun put(botId: String, key: String, result: EnvelopeResult) {
        map["$botId::$key"] = result
    }
}
