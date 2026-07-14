package dev.njr.zync.server.operator

import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.OutputSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Anthropic Messages API implementation of [LlmClient] over `java.net.http`
 * (no SDK dependency — the surface we use is one endpoint). The output schema
 * is passed as a structured-output constraint (`output_config.format`) so the
 * model is steered toward valid JSON, but the runtime still validates every
 * reply. Refusals (`stop_reason: "refusal"`) surface as [LlmReply.Refusal];
 * transport errors and non-2xx responses as [LlmReply.Unavailable].
 *
 * Configuration is 12-factor: `ANTHROPIC_API_KEY` (absent ⇒ operators
 * disabled, see [fromEnv]) and `ZYNC_LLM_MODEL` (default [DEFAULT_MODEL]).
 */
class AnthropicLlmClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val endpoint: URI = URI.create("https://api.anthropic.com/v1/messages"),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {

    override fun complete(request: LlmRequest): LlmReply {
        val body = requestBody(request, model).toString()
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return LlmReply.Unavailable("transport failure: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return LlmReply.Unavailable("interrupted")
        }
        return parseResponse(response.statusCode(), response.body())
    }

    /** Pure response mapping, factored out for tests. */
    internal fun parseResponse(status: Int, body: String): LlmReply {
        if (status !in 200..299) return LlmReply.Unavailable("http $status: ${body.take(300)}")
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: IllegalArgumentException) {
            return LlmReply.Unavailable("unparseable response body: ${e.message}")
        }
        if ((root["stop_reason"] as? JsonPrimitive)?.contentOrNull == "refusal") {
            val explanation = (root["stop_details"] as? JsonObject)
                ?.get("explanation")?.let { (it as? JsonPrimitive)?.contentOrNull }
            return LlmReply.Refusal(explanation)
        }
        val text = (root["content"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return if (text != null) LlmReply.Text(text) else LlmReply.Unavailable("no text content block")
    }

    companion object {
        /** Latest Sonnet — the fast/cost tier appropriate for per-op annotations. */
        const val DEFAULT_MODEL = "claude-sonnet-5"

        /**
         * Build a client from the environment, or null (⇒ operators disabled)
         * when no `ANTHROPIC_API_KEY` is configured.
         */
        fun fromEnv(env: (String) -> String? = System::getenv): AnthropicLlmClient? {
            val key = env("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
            val model = env("ZYNC_LLM_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
            return AnthropicLlmClient(apiKey = key, model = model)
        }

        /** The Messages API request body for one attempt (pure; tested directly). */
        internal fun requestBody(request: LlmRequest, model: String): JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("system", request.system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", request.user)
                }
            }
            putJsonObject("output_config") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", jsonSchemaFor(request.schema))
                }
            }
        }

        /** Map core's slim [OutputSchema] onto a JSON Schema object. */
        internal fun jsonSchemaFor(schema: OutputSchema): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for ((field, type) in schema.fields) {
                    putJsonObject(field) {
                        put(
                            "type",
                            when (type) {
                                FieldType.String -> "string"
                                FieldType.Number -> "number"
                                FieldType.Boolean -> "boolean"
                                FieldType.Object -> "object"
                                FieldType.Array -> "array"
                            },
                        )
                    }
                }
            }
            putJsonArray("required") { schema.required.forEach { add(it) } }
            put("additionalProperties", false)
        }
    }
}
