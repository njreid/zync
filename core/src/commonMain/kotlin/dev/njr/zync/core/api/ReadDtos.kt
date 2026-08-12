package dev.njr.zync.core.api

import kotlinx.serialization.Serializable

/**
 * The read side of the external-op-api (spec §6): a compact, wire-stable projection of one
 * content node for bots/integrations (and the MCP interface) that read-then-write. Decoupled
 * from the internal `NodeView` so the wire contract can stay stable as the projection grows.
 * ULIDs are strings on the wire. Shared by the server and the Kotlin SDK; the Go SDK mirrors it.
 */
@Serializable
data class NodeDto(
    val id: String,
    val kind: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val parent: String? = null,
    val dueDate: Long? = null,
    val deferUntil: Long? = null,
    val person: String? = null,
    val size: String? = null,
    /** Context/tag ids applied to the node. */
    val tags: List<String> = emptyList(),
    /** Free-form per-label tags (how bots + humans flag items). */
    val freeTags: List<String> = emptyList(),
    /** A Task's Reference links (mergeable `ref:` URLs). */
    val referenceLinks: List<String> = emptyList(),
    /** Agent-authored, awaiting human review. */
    val proposed: Boolean = false,
)

/** A list of nodes (search results, children, inbox…). */
@Serializable
data class NodeListDto(val nodes: List<NodeDto> = emptyList())
