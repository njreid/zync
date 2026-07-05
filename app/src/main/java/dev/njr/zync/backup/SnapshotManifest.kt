package dev.njr.zync.backup

import kotlinx.serialization.Serializable

/** One attachment file captured in a snapshot: its DB-relative path + blob key. */
@Serializable
data class AttachmentRef(val relativePath: String, val blobKey: String)

/**
 * The (encrypted, JSON) description of a single point-in-time snapshot: when it
 * was taken, the blob key of the whole `zync.db` snapshot, and the blob keys of
 * every attachment file at that moment. Restoring a snapshot fetches exactly
 * these blobs.
 */
@Serializable
data class SnapshotManifest(
    val createdAt: Long,
    val dbBlobKey: String,
    val attachments: List<AttachmentRef>,
)
