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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The phone's sync client (spec §6). On reconnect it **pushes** locally-authored
 * unsynced ops (device-signed) and marks the acked ones synced, then **pulls** ops
 * with `seq > cursor`, `observe()`-ing each HLC and applying it, advancing the
 * persisted cursor. Every request is signed with the device key (M4 auth). Idempotent:
 * re-push is deduped server-side; re-pull is deduped by `applied_op`.
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
) {
    suspend fun sync() {
        push()
        pull()
    }

    suspend fun push() {
        val payloads = db.transportQueries.selectUnsynced().executeAsList()
        if (payloads.isEmpty()) return
        val ops = payloads.map { json.decodeFromString(Op.serializer(), it) }
        val body = json.encodeToString(PushRequest.serializer(), PushRequest(ops))
        val response = http.post("$baseUrl/sync/push") {
            authHeaders("POST", "/sync/push").forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val ack = json.decodeFromString(PushResponse.serializer(), response.bodyAsText())
        db.transaction { ack.ackedOpIds.forEach { db.transportQueries.markSynced(it.toString()) } }
    }

    suspend fun pull() {
        var cursor = db.transportQueries.getCursor(peer).executeAsOneOrNull() ?: 0L
        while (true) {
            val response = http.get("$baseUrl/sync/pull?since=$cursor&limit=$pageLimit") {
                authHeaders("GET", "/sync/pull").forEach { (k, v) -> header(k, v) }
            }
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

    private fun authHeaders(method: String, path: String): Map<String, String> =
        signedHeaders(signer, method, path, now(), nonce())
}
