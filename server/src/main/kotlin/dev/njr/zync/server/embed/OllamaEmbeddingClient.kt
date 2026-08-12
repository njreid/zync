package dev.njr.zync.server.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Local-first [EmbeddingClient] over Ollama's batch embeddings endpoint (`POST /api/embed` with
 * `{model, input:[...]}` → `{embeddings:[[...]]}`). Fits the self-hosted ethos: no third party
 * sees your data. 12-factor config: `ZYNC_EMBED_URL` (e.g. `http://localhost:11434`, absent ⇒
 * semantic search disabled, see [fromEnv]) and `ZYNC_EMBED_MODEL` (default [DEFAULT_MODEL]).
 * Any transport/non-2xx/parse failure returns null so search degrades to keyword-only.
 */
class OllamaEmbeddingClient(
    private val baseUrl: String,
    private val model: String = DEFAULT_MODEL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EmbeddingClient {

    override fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") { texts.forEach { add(JsonPrimitive(it)) } }
        }.toString()
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/embed"))
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); return null
        }
        if (response.statusCode() !in 200..299) return null
        return try {
            val arr = json.parseToJsonElement(response.body()).jsonObject["embeddings"]?.jsonArray ?: return null
            arr.map { row -> (row as JsonArray).map { (it as JsonPrimitive).floatOrNull ?: 0f }.toFloatArray() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_MODEL = "nomic-embed-text"

        /** Build from env, or null when `ZYNC_EMBED_URL` is unset (⇒ semantic search disabled). */
        fun fromEnv(env: (String) -> String? = System::getenv): OllamaEmbeddingClient? {
            val url = env("ZYNC_EMBED_URL")?.takeIf { it.isNotBlank() } ?: return null
            return OllamaEmbeddingClient(url, env("ZYNC_EMBED_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL)
        }
    }
}
