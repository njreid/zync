package dev.njr.zync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "allowed_device", indices = [Index(value = ["pubkey"], unique = true)])
data class AllowedDeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pubkey: String, // base64 Ed25519
    val addedAt: Long,
    val lastSeen: Long?,
    val revoked: Boolean = false,
)
