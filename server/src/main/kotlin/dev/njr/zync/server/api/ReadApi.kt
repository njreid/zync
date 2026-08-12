package dev.njr.zync.server.api

import dev.njr.zync.core.api.NodeDto
import dev.njr.zync.web.content.NodeView

/**
 * Map the shared UI's [NodeView] onto the wire-stable [NodeDto] (external-op-api §6). Keeps the
 * read API contract independent of the internal projection type, so bots + the MCP interface see
 * a small, documented shape that doesn't churn when `NodeView` grows UI-only fields.
 */
fun NodeView.toDto(): NodeDto = NodeDto(
    id = id.toString(),
    kind = kind,
    title = title,
    notes = notes,
    status = status,
    summary = summary,
    parent = parent?.toString(),
    dueDate = dueDate,
    deferUntil = deferUntil,
    person = person,
    size = size,
    tags = tags.map { it.toString() },
    freeTags = freeTags,
    referenceLinks = referenceLinks,
    proposed = proposed,
)
