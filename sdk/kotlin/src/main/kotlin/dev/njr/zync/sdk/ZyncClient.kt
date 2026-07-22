package dev.njr.zync.sdk

import dev.njr.zync.core.api.BlobKeyResult
import dev.njr.zync.core.api.EnvelopeResult
import dev.njr.zync.core.api.IntentResult
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** A non-2xx response from the Zync op API. */
class ZyncApiException(val status: Int, val body: String) : RuntimeException("zync API HTTP $status: $body")

/**
 * A tiny, dependency-light client for the Zync external op API (see INTEGRATE.md).
 * Standard-library HTTP ([java.net.http.HttpClient]) + `kotlinx.serialization` for the
 * envelope contract in `dev.njr.zync.core.api`. Auto-generates an idempotency key per
 * submit so retries are safe.
 */
class ZyncClient(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    /** Submit an envelope; throws [ZyncApiException] on a non-2xx (e.g. a rejected batch). */
    fun submit(envelope: OpEnvelope): EnvelopeResult {
        val env = if (envelope.idempotencyKey == null) envelope.copy(idempotencyKey = UUID.randomUUID().toString()) else envelope
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/ops"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(OpEnvelope.serializer(), env)))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 300) throw ZyncApiException(resp.statusCode(), resp.body())
        return json.decodeFromString(EnvelopeResult.serializer(), resp.body())
    }

    // --- Convenience verbs (each is a single-intent envelope) ---

    fun create(title: String, parent: String = "inbox", kind: String = "task", fields: Map<String, JsonElement>? = null, tags: List<String>? = null): IntentResult =
        submit(OpEnvelope(intents = listOf(OpIntent(op = "create", title = title, parent = parent, kind = kind, fields = fields, tags = tags)))).results.single()

    fun comment(target: String, text: String): IntentResult =
        submit(OpEnvelope(intents = listOf(OpIntent(op = "comment", target = target, text = text)))).results.single()

    fun setField(target: String, field: String, value: JsonElement): IntentResult =
        submit(OpEnvelope(intents = listOf(OpIntent(op = "setField", target = target, field = field, value = value)))).results.single()

    fun setField(target: String, field: String, value: String): IntentResult = setField(target, field, JsonPrimitive(value))

    /** Propose a field edit for a human to accept (does not mutate live state). */
    fun propose(target: String, field: String, value: JsonElement): IntentResult =
        submit(OpEnvelope(mode = "propose", intents = listOf(OpIntent(op = "setField", target = target, field = field, value = value)))).results.single()

    fun complete(target: String): IntentResult = submit(OpEnvelope(intents = listOf(OpIntent(op = "complete", target = target)))).results.single()
    fun trash(target: String): IntentResult = submit(OpEnvelope(intents = listOf(OpIntent(op = "trash", target = target)))).results.single()
    fun move(target: String, parent: String): IntentResult = submit(OpEnvelope(intents = listOf(OpIntent(op = "move", target = target, parent = parent)))).results.single()
    fun addTag(target: String, context: String): IntentResult = submit(OpEnvelope(intents = listOf(OpIntent(op = "addTag", target = target, context = context)))).results.single()

    /** Upload a blob (content-addressed); returns its key for use in an `attach` intent. */
    fun uploadBlob(bytes: ByteArray): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/blobs"))
            .header("Authorization", "Bearer $token")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 300) throw ZyncApiException(resp.statusCode(), resp.body())
        return json.decodeFromString(BlobKeyResult.serializer(), resp.body()).key
    }

    fun attach(target: String, blobRef: String, type: String = "pdf", name: String = "attachment"): IntentResult =
        submit(OpEnvelope(intents = listOf(OpIntent(op = "attach", target = target, blobRef = blobRef, type = type, name = name)))).results.single()
}
