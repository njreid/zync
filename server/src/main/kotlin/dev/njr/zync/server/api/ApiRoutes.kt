package dev.njr.zync.server.api

import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.OpEnvelope
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

private const val MAX_INTENTS = 200

/**
 * The external-op-api write door (spec §6): `POST /api/ops` takes a bearer-authed bot's
 * envelope and ingests it. Session-exempt (bearer, not browser session). Idempotent per
 * `(botId, idempotencyKey)`.
 */
fun Route.apiRoutes(api: ExternalOpApi, auth: BotAuth) {
    val idem = IdempotencyCache()
    post("/api/ops") {
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")?.trim().orEmpty()
        val bot = token.takeIf { it.isNotEmpty() }?.let { auth.authenticate(it) }
        if (bot == null) return@post call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)

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
