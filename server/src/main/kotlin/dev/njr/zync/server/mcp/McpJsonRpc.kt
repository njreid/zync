package dev.njr.zync.server.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minimal JSON-RPC 2.0 helpers for the stateless MCP transport (spec 2026-08-11 §1). We work in
 * [JsonObject]s rather than a typed DTO tree — MCP's params/results are open-ended maps and the
 * surface we serve is small, so building responses with `buildJsonObject` is simpler and avoids a
 * large serializer surface. The request `id` is echoed verbatim (string or number, per the spec).
 */
object JsonRpc {
    const val VERSION = "2.0"

    // Standard JSON-RPC error codes.
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun result(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }
}

/** The outcome of handling one JSON-RPC message: a [Response] object to send, or [NoContent]
 *  for a notification (no `id`) — the transport replies 202 with an empty body. */
sealed interface McpOutcome {
    data class Response(val body: JsonObject) : McpOutcome
    data object NoContent : McpOutcome
}
