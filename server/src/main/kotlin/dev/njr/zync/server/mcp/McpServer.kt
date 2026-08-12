package dev.njr.zync.server.mcp

import dev.njr.zync.core.api.NodeDto
import dev.njr.zync.core.api.NodeListDto
import dev.njr.zync.core.api.OpEnvelope
import dev.njr.zync.core.api.OpIntent
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.server.api.BotIdentity
import dev.njr.zync.server.api.ExternalOpApi
import dev.njr.zync.server.api.VerbRateLimiter
import dev.njr.zync.server.api.toDto
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.NodeView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The stateless MCP server (spec 2026-08-11): dispatches one JSON-RPC message per request against
 * the read model + the external-op-api. Holds no per-connection state — [handle] is a pure
 * function of (authenticated bot, message). Every write tool is submitted with `mode = "propose"`,
 * so the MCP door can only *propose* changes for a human to confirm, whatever the bot's own mode.
 */
class McpServer(
    private val read: ContentReadModel,
    private val api: ExternalOpApi,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val serverName: String = "zync",
    private val serverVersion: String = "0.1",
    private val protocolVersion: String = "2025-06-18",
    /** Search backend for the `search` tool; defaults to keyword. Main injects hybrid search. */
    private val search: (String, Int) -> List<NodeView> = read::search,
    /** Per-token, per-verb rate limiter for write tools (spec §8) — the SAME instance Main wires
     *  into `/api/ops`, so a bot can't dodge its budget by switching doors. Null (tests) disables
     *  MCP-side rate limiting entirely. */
    private val rateLimiter: VerbRateLimiter? = null,
) {
    private val idempotency = McpIdempotencyCache()

    fun handle(bot: BotIdentity, message: JsonObject): McpOutcome {
        val id: JsonElement? = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.content
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

        // A notification (no id) gets no response, per JSON-RPC. `notifications/initialized` and
        // any other client notification are accepted and ignored (stateless — nothing to record).
        if (id == null) return McpOutcome.NoContent

        if (method == null) return resp(JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "missing method"))
        return resp(when (method) {
            "initialize" -> JsonRpc.result(id, buildJsonObject {
                put("protocolVersion", protocolVersion)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { })
                    put("resources", buildJsonObject { })
                })
                put("serverInfo", buildJsonObject { put("name", serverName); put("version", serverVersion) })
            })
            "ping" -> JsonRpc.result(id, buildJsonObject { })
            "tools/list" -> JsonRpc.result(id, buildJsonObject { put("tools", toolList(bot)) })
            "tools/call" -> JsonRpc.result(id, callTool(bot, id, params))
            "resources/list" -> JsonRpc.result(id, resourceList())
            "resources/templates/list" -> JsonRpc.result(id, resourceTemplateList())
            "resources/read" -> readResource(id, params)
            else -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "unknown method '$method'")
        })
    }

    private fun resp(body: JsonObject) = McpOutcome.Response(body)

    // --- tools/list ---

    private fun toolList(bot: BotIdentity) = buildJsonArray {
        McpCatalog.tools
            .filter { val v = it.verb; v == null || v in bot.capabilities.verbs }
            .forEach { t ->
                add(buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.inputSchema)
                })
            }
    }

    // --- tools/call ---

    private fun callTool(bot: BotIdentity, id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content
            ?: return toolError("missing tool name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val tool = McpCatalog.byName[name] ?: return toolError("unknown tool '$name'")

        // Capability gate → a tool error (not a transport error): the connection stays usable and
        // the model can adjust. Read tools (verb == null) are always permitted.
        val verb = tool.verb
        if (verb != null && verb !in bot.capabilities.verbs) return toolError("tool '$name' not permitted")

        if (tool.isRead) return runCatching { executeRead(tool, args) }
            .getOrElse { toolError(it.message ?: "read failed") }
        checkNotNull(verb) { "write tool '$name' has no backing verb" } // isRead == (verb == null)

        // Write: force propose. Idempotent per (bot, JSON-RPC id, tool, args) so a retried call
        // re-proposes nothing — the tool/args are part of the key (not just the JSON-RPC id) so
        // two DIFFERENT calls that happen to reuse an id (plausible: ids are per-request, not
        // conversation-scoped, and plenty of clients just send id=1 every time) don't collide and
        // silently return one call's cached result for the other's request.
        val key = "${bot.id}:$id:$name:$args"
        // Dedup-check → rate-check → submit → cache, all under a per-key lock — mirrors
        // ApiRoutes.IdempotencyCache.withKeyLock — so a retry storm on the same key can't have two
        // requests both pass the cache-miss check before either caches its result (each submit
        // mints fresh ids, so op-id dedup wouldn't catch the double-apply).
        return idempotency.withKeyLock(key) {
            idempotency.get(key)?.let { return@withKeyLock it }
            // Same per-token, per-verb budget as /api/ops (spec §8) — a cached retry above never
            // reaches here, so it costs no budget.
            if (rateLimiter != null && !rateLimiter.tryConsume(bot.id, mapOf(verb to 1), bot.capabilities)) {
                return@withKeyLock toolError("rate limit exceeded")
            }
            val intent = McpCatalog.intentFor(tool, args) ?: return@withKeyLock toolError("cannot build intent for '$name'")
            val out = runCatching { submitProposal(bot, intent) }.getOrElse { toolError(it.message ?: "submit failed") }
            idempotency.put(key, out)
            out
        }
    }

    private fun submitProposal(bot: BotIdentity, intent: OpIntent): JsonObject {
        val env = OpEnvelope(mode = "propose", intents = listOf(intent))
        val r = api.submit(bot, env).results.single()
        return when (r.status) {
            "error" -> toolError(r.error ?: "rejected")
            else -> {
                val verb = "${intent.op}${r.nodeId?.let { " (node $it)" } ?: ""}"
                val text = if (r.status == "proposed") "Proposed $verb — awaiting confirmation."
                else "Committed $verb."
                toolResult(text, buildJsonObject {
                    put("status", r.status)
                    r.nodeId?.let { put("nodeId", it) }
                })
            }
        }
    }

    private fun executeRead(tool: McpTool, args: JsonObject): JsonObject {
        fun s(k: String) = (args[k] as? JsonPrimitive)?.content
        fun ulid(k: String) = s(k)?.let { Ulid.parse(it) }
        return when (tool.name) {
            "list_inbox" -> nodesResult(read.inbox(null))
            "list_children" -> nodesResult(read.children(resolveParent(s("parent"))))
            "get_node" -> {
                val node = ulid("id")?.let { read.node(it) } ?: return toolError("node not found")
                toolResult("node ${node.id}", json.encodeToJsonElement(NodeDto.serializer(), node.toDto()) as JsonObject)
            }
            "search" -> nodesResult(search(s("query").orEmpty(), (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 200) ?: 50))
            "list_comments" -> nodesResult(read.comments(ulid("id") ?: return toolError("id required")))
            "list_proposals" -> {
                val proposals = read.proposals().map { it.toDto() }
                val suggestions = read.suggestions().map { sug ->
                    buildJsonObject {
                        put("id", sug.id.toString()); put("targetId", sug.targetId.toString())
                        put("kind", sug.kind); put("summary", sug.summary)
                    }
                }
                toolResult("${proposals.size} proposal(s), ${suggestions.size} suggestion(s)", buildJsonObject {
                    put("proposals", json.encodeToJsonElement(NodeListDto.serializer(), NodeListDto(proposals)).let { (it as JsonObject)["nodes"]!! })
                    putJsonArray("suggestions") { suggestions.forEach { add(it) } }
                })
            }
            "list_reference" -> {
                val folder = ulid("folder")
                nodesResult(if (folder != null) read.referenceChildren(folder) else read.reference())
            }
            else -> toolError("unknown read tool '${tool.name}'")
        }
    }

    private fun resolveParent(p: String?): Ulid? = when (p) {
        null, "", "inbox" -> null
        "reference" -> WellKnownNodes.REFERENCE_ROOT
        else -> Ulid.parse(p)
    }

    private fun nodesResult(nodes: List<NodeView>): JsonObject {
        val dto = NodeListDto(nodes.map { it.toDto() })
        return toolResult("${dto.nodes.size} node(s)", json.encodeToJsonElement(NodeListDto.serializer(), dto) as JsonObject)
    }

    // --- resources ---

    private fun resourceList() = buildJsonObject {
        putJsonArray("resources") {
            add(resourceEntry("zync://inbox", "Inbox"))
            add(resourceEntry("zync://proposals", "Proposals"))
            add(resourceEntry("zync://reference", "Reference"))
        }
    }

    private fun resourceTemplateList() = buildJsonObject {
        putJsonArray("resourceTemplates") {
            add(buildJsonObject { put("uriTemplate", "zync://node/{id}"); put("name", "Node"); put("mimeType", "application/json") })
            add(buildJsonObject { put("uriTemplate", "zync://node/{id}/comments"); put("name", "Node comments"); put("mimeType", "application/json") })
        }
    }

    private fun resourceEntry(uri: String, name: String) = buildJsonObject {
        put("uri", uri); put("name", name); put("mimeType", "application/json")
    }

    private fun readResource(id: JsonElement, params: JsonObject): JsonObject {
        val uri = (params["uri"] as? JsonPrimitive)?.content
            ?: return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "missing uri")
        val body: JsonElement? = when {
            uri == "zync://inbox" -> json.encodeToJsonElement(NodeListDto.serializer(), NodeListDto(read.inbox(null).map { it.toDto() }))
            uri == "zync://reference" -> json.encodeToJsonElement(NodeListDto.serializer(), NodeListDto(read.reference().map { it.toDto() }))
            uri == "zync://proposals" -> json.encodeToJsonElement(NodeListDto.serializer(), NodeListDto(read.proposals().map { it.toDto() }))
            uri.startsWith("zync://node/") -> {
                val rest = uri.removePrefix("zync://node/")
                if (rest.endsWith("/comments")) {
                    val nid = runCatching { Ulid.parse(rest.removeSuffix("/comments")) }.getOrNull()
                        ?: return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "bad node id")
                    json.encodeToJsonElement(NodeListDto.serializer(), NodeListDto(read.comments(nid).map { it.toDto() }))
                } else {
                    val nid = runCatching { Ulid.parse(rest) }.getOrNull()
                        ?: return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "bad node id")
                    read.node(nid)?.let { json.encodeToJsonElement(NodeDto.serializer(), it.toDto()) }
                }
            }
            else -> null
        } ?: return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "unknown resource '$uri'")
        return JsonRpc.result(id, buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { put("uri", uri); put("mimeType", "application/json"); put("text", body.toString()) })
            }
        })
    }

    // --- tool-result helpers ---

    private fun toolResult(text: String, structured: JsonObject): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        put("structuredContent", structured)
        put("isError", false)
    }

    private fun toolError(message: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", message) }) }
        put("isError", true)
    }
}
