package dev.njr.zync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "context", indices = [Index(value = ["name"], unique = true)])
data class ContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(tableName = "node_context", primaryKeys = ["nodeId", "contextId"])
data class NodeContextCrossRef(
    val nodeId: Long,
    val contextId: Long,
)
