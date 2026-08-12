package dev.njr.zync.server.mcp

import dev.njr.zync.core.api.OpIntent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A tool the MCP interface advertises (spec 2026-08-11 §3). [verb] is the backing external-op-api
 * verb for write tools (used to gate the tool by the bot's `capabilities.verbs`); `null` marks a
 * read tool, always available to any authenticated bot. Write tools are submitted `mode=propose`.
 */
data class McpTool(
    val name: String,
    val verb: String?,
    val description: String,
    val inputSchema: JsonObject,
) {
    val isRead: Boolean get() = verb == null
}

/** The static tool catalog + intent translation for write tools. */
object McpCatalog {
    private fun str(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private fun obj(props: Map<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
    private val anyValue = buildJsonObject { put("description", "any JSON value") }

    val tools: List<McpTool> = listOf(
        // --- Read tools (always available) ---
        McpTool("list_inbox", null, "List untriaged inbox items.", obj(emptyMap())),
        McpTool("list_children", null, "List the child nodes of a parent (omit parent for the root).",
            obj(mapOf("parent" to str("parent node ULID; omit for root")))),
        McpTool("get_node", null, "Get one node's projected fields by ULID.",
            obj(mapOf("id" to str("node ULID")), listOf("id"))),
        McpTool("search", null, "Keyword search across titles, notes and summaries.",
            obj(mapOf("query" to str("search text"), "limit" to buildJsonObject { put("type", "integer") }), listOf("query"))),
        McpTool("list_comments", null, "List comments/annotations under a node.",
            obj(mapOf("id" to str("node ULID")), listOf("id"))),
        McpTool("list_proposals", null, "List pending proposals and suggestions awaiting human review.", obj(emptyMap())),
        McpTool("list_reference", null, "List the Reference tree (a folder's contents, or the reference root).",
            obj(mapOf("folder" to str("reference folder ULID; omit for the reference root")))),

        // --- Write tools (gated by capability verb; all proposed for human confirmation) ---
        McpTool("create_task", "create", "Propose a new task (created flagged for review).",
            obj(mapOf("title" to str("task title"), "parent" to str("parent ULID or alias inbox/reference"),
                "notes" to str("optional notes")), listOf("title"))),
        McpTool("create_project", "create", "Propose a new project (a node that will hold children).",
            obj(mapOf("title" to str("project title"), "parent" to str("parent ULID or alias")), listOf("title"))),
        McpTool("set_field", "setField", "Propose changing a field on a node (mints a suggestion).",
            obj(mapOf("id" to str("target node ULID"), "field" to str("field name"), "value" to anyValue),
                listOf("id", "field", "value"))),
        McpTool("complete", "complete", "Propose marking a node done.",
            obj(mapOf("id" to str("target node ULID")), listOf("id"))),
        McpTool("trash", "trash", "Propose dropping a node.",
            obj(mapOf("id" to str("target node ULID")), listOf("id"))),
        McpTool("comment", "comment", "Add a comment to a node (commits — comments can't clobber human state).",
            obj(mapOf("id" to str("target node ULID"), "text" to str("comment text")), listOf("id", "text"))),
        McpTool("add_free_tag", "addFreeTag", "Add a free-form label to a node (commits — additive metadata).",
            obj(mapOf("id" to str("target node ULID"), "tag" to str("label")), listOf("id", "tag"))),
        McpTool("remove_free_tag", "removeFreeTag", "Remove a free-form label from a node (commits).",
            obj(mapOf("id" to str("target node ULID"), "tag" to str("label")), listOf("id", "tag"))),
        McpTool("move", "move", "Propose moving a node under a new parent (organize).",
            obj(mapOf("id" to str("target node ULID"), "parent" to str("new parent ULID or alias")), listOf("id", "parent"))),
        McpTool("add_tag", "addTag", "Propose adding a context tag to a node.",
            obj(mapOf("id" to str("target node ULID"), "context" to str("context/tag ULID")), listOf("id", "context"))),
        McpTool("attach", "attach", "Propose attaching a stored blob (upload via PUT /api/blobs first).",
            obj(mapOf("id" to str("target node ULID"), "blobRef" to str("content-addressed blob key"),
                "type" to str("attachment type, e.g. pdf"), "name" to str("filename")), listOf("id", "blobRef"))),
    )

    val byName: Map<String, McpTool> = tools.associateBy { it.name }

    /** Translate a write tool call's arguments into an [OpIntent]; null if [tool] is a read tool. */
    fun intentFor(tool: McpTool, args: JsonObject): OpIntent? {
        fun s(k: String): String? = (args[k] as? JsonPrimitive)?.content
        val id = s("id")
        return when (tool.name) {
            "create_task" -> OpIntent(op = "create", title = s("title"), parent = s("parent"),
                fields = s("notes")?.let { mapOf("notes" to JsonPrimitive(it)) })
            "create_project" -> OpIntent(op = "create", kind = "project", title = s("title"), parent = s("parent"))
            "set_field" -> OpIntent(op = "setField", target = id, field = s("field"), value = args["value"])
            "complete" -> OpIntent(op = "complete", target = id)
            "trash" -> OpIntent(op = "trash", target = id)
            "comment" -> OpIntent(op = "comment", target = id, text = s("text"))
            "add_free_tag" -> OpIntent(op = "addFreeTag", target = id, tag = s("tag"))
            "remove_free_tag" -> OpIntent(op = "removeFreeTag", target = id, tag = s("tag"))
            "move" -> OpIntent(op = "move", target = id, parent = s("parent"))
            "add_tag" -> OpIntent(op = "addTag", target = id, context = s("context"))
            "attach" -> OpIntent(op = "attach", target = id, blobRef = s("blobRef"), type = s("type"), name = s("name"))
            else -> null
        }
    }
}
