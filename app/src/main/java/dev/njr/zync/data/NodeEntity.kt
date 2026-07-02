package dev.njr.zync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class NodeKind { FOLDER, PROJECT, TASK }
enum class NodeStatus { ACTIVE, DONE, DROPPED }

@Entity(tableName = "node")
data class NodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: NodeKind,
    val parentId: Long?,
    val title: String,
    val notes: String = "",
    val status: NodeStatus = NodeStatus.ACTIVE,
    val deferUntil: Long? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val sortOrder: Long = 0,
    val builtin: Boolean = false,
)
