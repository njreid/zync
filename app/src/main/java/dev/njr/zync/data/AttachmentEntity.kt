package dev.njr.zync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AttachmentType { AUDIO, TRANSCRIPT, PDF, OCR_TEXT }

@Entity(tableName = "attachment", indices = [Index("nodeId")])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: Long,
    val type: AttachmentType,
    /** Relative to the Documents/Zync data root (spec §10a — keeps the folder portable). */
    val relativePath: String,
)
