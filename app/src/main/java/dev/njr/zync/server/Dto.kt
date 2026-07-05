package dev.njr.zync.server

import dev.njr.zync.data.AttachmentEntity
import dev.njr.zync.data.AttachmentType
import dev.njr.zync.data.ContextEntity
import dev.njr.zync.data.NodeEntity
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import kotlinx.serialization.Serializable

@Serializable
data class NodeDto(
    val id: Long,
    val kind: NodeKind,
    val parentId: Long?,
    val title: String,
    val notes: String,
    val status: NodeStatus,
    val deferUntil: Long?,
    val createdAt: Long,
    val completedAt: Long?,
    val sortOrder: Long,
    val builtin: Boolean,
)

fun NodeEntity.toDto() = NodeDto(
    id, kind, parentId, title, notes, status, deferUntil, createdAt, completedAt, sortOrder, builtin
)

@Serializable
data class AttachmentDto(
    val id: Long,
    val nodeId: Long,
    val type: AttachmentType,
    val relativePath: String,
)

fun AttachmentEntity.toDto() = AttachmentDto(id, nodeId, type, relativePath)

@Serializable
data class ContextDto(val id: Long, val name: String)

fun ContextEntity.toDto() = ContextDto(id, name)

@Serializable
data class ErrorDto(val error: String)
