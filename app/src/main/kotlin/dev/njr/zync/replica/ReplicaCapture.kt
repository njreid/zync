package dev.njr.zync.replica

import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.json.JsonPrimitive

/**
 * The capture path, written as op-log entries (spec §0 invariant 3: capture never
 * blocks). Every capture creates an inbox node offline; attachments are stored in the
 * local blob store and referenced by an `AddAttachment` op. Uploads + push happen later
 * on reconnect ([SyncClient] / [BlobUploader]) — capture itself touches no network.
 *
 * The Android capture triggers (volume-key service, share, voice, doc scan) call into
 * this; it's the seam that replaces the old Room write path.
 */
class ReplicaCapture(
    private val opWriter: OpWriter,
    private val blobs: LocalBlobStore,
    private val inbox: () -> Ulid?,
) {
    /** Capture a quick text note; returns the new node id. */
    fun captureNote(title: String, notes: String? = null): Ulid {
        val node = opWriter.createNode(title, parent = inbox())
        if (!notes.isNullOrBlank()) opWriter.setField(node, "notes", JsonPrimitive(notes))
        return node
    }

    /**
     * Capture an attachment (image, voice, doc): store bytes locally, create the inbox
     * node, and link an attachment entity carrying the content hash. Returns the node id.
     */
    fun captureAttachment(title: String, bytes: ByteArray, type: String, filename: String, notes: String? = null): Ulid {
        val node = captureNote(title, notes)
        val blobHash = blobs.put(bytes)
        opWriter.createAttachment(node, type, blobHash, filename)
        return node
    }
}
