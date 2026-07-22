package dev.njr.zync.server.api

import dev.njr.zync.core.api.BlobKeyResult
import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.server.auth.bearerToken
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.BlobTooLargeException
import dev.njr.zync.web.sse.ChangeNotifier
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
import io.ktor.server.sse.sse

private const val MAX_INTENTS = 200

/** Resolve the bearer token to a bot, or null. Uses the shared [bearerToken] parser. */
private fun ApplicationCall.bot(auth: BotAuth): BotIdentity? =
    bearerToken(request.headers[HttpHeaders.Authorization])?.takeIf { it.isNotEmpty() }?.let { auth.authenticate(it) }

/** HTTP status for an envelope result: any errored intent ⇒ the whole envelope was rejected. */
private fun EnvelopeResult.httpStatus(): HttpStatusCode =
    if (results.any { it.status == "error" }) HttpStatusCode.BadRequest else HttpStatusCode.OK

/**
 * The external-op-api write door (spec §6): `POST /api/ops` takes a bearer-authed bot's
 * envelope and ingests it (idempotent per `(botId, idempotencyKey)`); `PUT /api/blobs`
 * content-addresses a blob to reference in an `attach` intent. Session-exempt (bearer).
 */
fun Route.apiRoutes(
    api: ExternalOpApi,
    auth: BotAuth,
    blobs: BlobService? = null,
    changes: ChangeNotifier? = null,
    head: () -> Long = { 0L },
) {
    val idem = IdempotencyCache()
    val limiter = VerbRateLimiter()

    // The react side (spec §6, Q4): a bearer-authed SSE feed. Emits a `changed` event with
    // the current head seq whenever the op log changes, so a bot knows to re-query.
    if (changes != null) sse("/api/changes") {
        if (call.bot(auth) == null) { close(); return@sse }
        suspend fun ping() = send(event = "changed", data = """{"head":${head()}}""")
        ping()
        changes.changes.collect { ping() }
    }

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

        // Dedup-check → rate-check → submit → cache, all under a per-key lock so a retry storm
        // with the SAME idempotencyKey can't both pass the check and double-apply (each submit
        // mints fresh op ids, so op-id dedupe wouldn't catch it). The block holds no suspension
        // points; the (suspending) respond happens afterwards. A null key skips the lock — no
        // dedup is possible or promised for keyless envelopes.
        val outcome = idem.withKeyLock(bot.id, env.idempotencyKey) {
            val cached = env.idempotencyKey?.let { idem.get(bot.id, it) }
            if (cached != null) return@withKeyLock Outcome.Reply(cached.httpStatus(), cached)

            // Per-token, per-verb rate limit (RESOLVED Q5); a cached retry above never reaches here,
            // so it costs no budget.
            val counts = env.intents.groupingBy { it.op }.eachCount()
            if (!limiter.tryConsume(bot.id, counts, bot.capabilities)) return@withKeyLock Outcome.RateLimited

            val result = api.submit(bot, env)
            env.idempotencyKey?.let { idem.put(bot.id, it, result) }
            Outcome.Reply(result.httpStatus(), result)
        }
        when (outcome) {
            Outcome.RateLimited -> call.respondText("rate limit exceeded", status = HttpStatusCode.TooManyRequests)
            is Outcome.Reply -> call.respond(outcome.status, outcome.result)
        }
    }
}

/** The result of the /api/ops critical section, respond-able outside the idempotency lock. */
private sealed interface Outcome {
    data object RateLimited : Outcome
    data class Reply(val status: HttpStatusCode, val result: EnvelopeResult) : Outcome
}

/** Per-`(botId, verb)` fixed-window rate limiter, checked atomically for the whole envelope (spec §7, Q5). */
private class VerbRateLimiter(private val now: () -> Long = System::currentTimeMillis) {
    private class Window(var start: Long, var count: Int)
    private val windows = HashMap<String, Window>()

    @Synchronized
    fun tryConsume(botId: String, counts: Map<String, Int>, caps: dev.njr.zync.core.api.BotCapabilities): Boolean {
        val t = now()
        // Check every verb fits first (atomic — no partial consumption).
        for ((verb, n) in counts) {
            val limit = caps.limitFor(verb) ?: continue
            val w = windows["$botId::$verb"]
            val used = if (w == null || t - w.start >= 60_000) 0 else w.count
            if (used + n > limit) return false
        }
        for ((verb, n) in counts) {
            if (caps.limitFor(verb) == null) continue
            val w = windows.getOrPut("$botId::$verb") { Window(t, 0) }
            if (t - w.start >= 60_000) { w.start = t; w.count = 0 }
            w.count += n
        }
        return true
    }
}

/** Bounded `(botId, key) → result` LRU so a retried envelope returns the original result (spec §5). */
private class IdempotencyCache(private val max: Int = 1024) {
    private val map = object : LinkedHashMap<String, EnvelopeResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EnvelopeResult>): Boolean = size > max
    }

    // Striped monitors (bounded, unlike a per-key map that would grow forever): same key ⇒ same
    // stripe, so concurrent same-key submits serialize; different keys rarely collide.
    private val stripes = Array(64) { Any() }

    /** Run [block] holding [key]'s stripe (or unlocked when [key] is null). No suspension inside. */
    fun <T> withKeyLock(botId: String, key: String?, block: () -> T): T {
        if (key == null) return block()
        val lock = stripes[(("$botId::$key".hashCode()) and 0x7fffffff) % stripes.size]
        return synchronized(lock, block)
    }

    @Synchronized fun get(botId: String, key: String): EnvelopeResult? = map["$botId::$key"]

    @Synchronized fun put(botId: String, key: String, result: EnvelopeResult) {
        map["$botId::$key"] = result
    }
}
