package dev.njr.zync.server.mcp

import dev.njr.zync.server.api.BotAuth
import dev.njr.zync.server.api.BotIdentity
import dev.njr.zync.server.auth.bearerToken
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Resolve the bearer token to a bot, or null (mirrors the /api/ops door). */
private fun ApplicationCall.bot(auth: BotAuth): BotIdentity? =
    bearerToken(request.headers[HttpHeaders.Authorization])?.takeIf { it.isNotEmpty() }?.let { auth.authenticate(it) }

/**
 * The stateless MCP transport (spec 2026-08-11 §1): a single `POST /mcp` that takes one JSON-RPC
 * 2.0 message and returns one JSON response — no session id, no server-initiated stream. `GET /mcp`
 * (which would open a server→client SSE channel) is 405: a stateless server has no such channel.
 * Bearer-authed like `/api/ops`; joins `SESSION_EXEMPT`.
 */
fun Route.mcpRoutes(server: McpServer, auth: BotAuth, json: Json = Json { ignoreUnknownKeys = true }) {
    post("/mcp") {
        val bot = call.bot(auth) ?: return@post call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
        val raw = call.receiveText()
        val message = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return@post call.respondText(
            JsonRpc.error(null, JsonRpc.PARSE_ERROR, "parse error").toString(),
            ContentType.Application.Json, HttpStatusCode.BadRequest,
        )

        // A tool call may do network I/O (embedding search); keep it off the event loop. Respond
        // the JSON text explicitly (not via ContentNegotiation) so the raw JsonObject is emitted
        // verbatim regardless of the app's installed converters.
        when (val out = withContext(Dispatchers.IO) { server.handle(bot, message) }) {
            McpOutcome.NoContent -> call.respondText("", status = HttpStatusCode.Accepted)
            is McpOutcome.Response -> call.respondText(out.body.toString(), ContentType.Application.Json)
        }
    }

    // Stateless: no long-lived server→client channel to open.
    get("/mcp") { call.respondText("method not allowed", status = HttpStatusCode.MethodNotAllowed) }
}
