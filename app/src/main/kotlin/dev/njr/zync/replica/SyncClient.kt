package dev.njr.zync.replica

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.core.sync.PullResponse
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.core.sync.PushResponse
import dev.njr.zync.data.db.ZyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A sync request the server refused. [permanent] distinguishes failures that will
 * never succeed by retrying the same request (auth, malformed) from transient ones
 * (5xx, 408 timeout, 429 rate limit) — [dev.njr.zync.sync.SyncWorker] keys its
 * WorkManager retry policy off this.
 */
class SyncRequestException(val status: Int, message: String) : Exception("$message: HTTP $status") {
    val permanent: Boolean get() = status in 400..499 && status != 408 && status != 429
}

internal fun HttpResponse.requireOk(what: String) {
    if (!status.isSuccess()) throw SyncRequestException(status.value, what)
}

/**
 * The phone's sync client (spec §6). On reconnect it **pushes** locally-authored
 * unsynced ops (device-signed) and marks the acked ones synced, then **pulls** ops
 * with `seq > cursor`, `observe()`-ing each HLC and applying it, advancing the
 * persisted cursor. Every request is signed with the device key (M4 auth). Idempotent:
 * re-push is deduped server-side; re-pull is deduped by `applied_op`.
 *
 * Pushes are paged ([pushBatchOps] ops / [pushBatchBytes] encoded bytes per request,
 * each batch acked and marked synced before the next) so an arbitrarily large backlog
 * can never exceed the server's request-size cap and progress survives interruption.
 */
@OptIn(ExperimentalEncodingApi::class)
class SyncClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val db: ZyncDatabase,
    private val store: StateStore,
    private val hlc: LocalHlc,
    private val signer: DeviceSigner,
    private val now: () -> Long,
    private val nonce: () -> String,
    private val json: Json = Json,
    private val peer: String = "server",
    private val pageLimit: Long = 500,
    private val pushBatchOps: Long = 200,
    private val pushBatchBytes: Long = 1024 * 1024,
) {
    suspend fun sync() {
        push()
        pull()
    }

    suspend fun push() {
        while (true) {
            val payloads = db.transportQueries.selectUnsyncedBatch(pushBatchOps).executeAsList()
            if (payloads.isEmpty()) return
            // Trim the batch to the byte budget (always keeping at least one op so a
            // single oversized op fails loudly server-side instead of looping forever).
            var bytes = 0L
            val batch = payloads.takeWhile { p -> (bytes + p.length).also { bytes = it } <= pushBatchBytes }
                .ifEmpty { payloads.take(1) }
            val ops = batch.map { json.decodeFromString(Op.serializer(), it) }
            val body = json.encodeToString(PushRequest.serializer(), PushRequest(ops)).encodeToByteArray()
            val response = http.post("$baseUrl/sync/push") {
                authHeaders("POST", "/sync/push", body = body).forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            response.requireOk("push")
            val ack = json.decodeFromString(PushResponse.serializer(), response.bodyAsText())
            db.transaction { ack.ackedOpIds.forEach { db.transportQueries.markSynced(it.toString()) } }
            if (ack.ackedOpIds.isEmpty()) return // defensive: no progress, don't spin
        }
    }

    suspend fun pull() {
        var cursor = db.transportQueries.getCursor(peer).executeAsOneOrNull() ?: 0L
        while (true) {
            val query = "since=$cursor&limit=$pageLimit"
            val response = http.get("$baseUrl/sync/pull?$query") {
                authHeaders("GET", "/sync/pull", query = query).forEach { (k, v) -> header(k, v) }
            }
            response.requireOk("pull")
            val page = json.decodeFromString(PullResponse.serializer(), response.bodyAsText())
            if (page.ops.isEmpty()) break
            db.transaction {
                for (op in page.ops) {
                    hlc.observe(op.hlc)
                    apply(op, store)
                    op.seq?.let { cursor = maxOf(cursor, it) }
                }
                db.transportQueries.setCursor(peer, cursor)
            }
            if (page.ops.size < pageLimit) break
        }
    }

    /** The agenda side channel: all sources' upcoming externally-pushed events. */
    suspend fun fetchAgenda(): dev.njr.zync.core.agenda.AgendaSnapshot {
        val response = http.get("$baseUrl/agenda") {
            authHeaders("GET", "/agenda").forEach { (k, v) -> header(k, v) }
        }
        response.requireOk("agenda")
        return json.decodeFromString(dev.njr.zync.core.agenda.AgendaSnapshot.serializer(), response.bodyAsText())
    }

    private fun authHeaders(method: String, path: String, query: String = "", body: ByteArray = ByteArray(0)): Map<String, String> =
        signedHeaders(signer, method, path, now(), nonce(), query, body)
}
