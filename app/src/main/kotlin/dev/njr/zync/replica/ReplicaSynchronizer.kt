package dev.njr.zync.replica

import dev.njr.zync.core.op.Op
import dev.njr.zync.data.db.ZyncDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One production sync pass: upload the blobs referenced by pending `AddAttachment`
 * ops, THEN push/pull the op log. The ordering is the invariant — the server must
 * never hold attachment metadata whose bytes only exist on the phone. A blob-upload
 * failure aborts the pass (ops stay unsynced; the next attempt re-uploads
 * idempotently); a blob missing from local storage is skipped with a warning so one
 * corrupt capture cannot stall the whole log forever.
 */
class ReplicaSynchronizer(
    private val client: SyncClient,
    private val blobs: BlobUploader,
    private val db: ZyncDatabase,
    private val json: Json = Json,
    private val warn: (String) -> Unit = {},
) {
    suspend fun syncOnce() {
        uploadPendingBlobs()
        client.sync()
    }

    private suspend fun uploadPendingBlobs() {
        val pending = db.transportQueries.selectUnsynced().executeAsList()
            .map { json.decodeFromString(Op.serializer(), it) }
            .filterIsInstance<Op.AddAttachment>()
            .mapNotNull { it.value.jsonObject["blobHash"]?.jsonPrimitive?.contentOrNull }
            .distinct()
        for (key in pending) {
            if (!blobs.upload(key)) warn("blob $key referenced by a pending op is missing locally; skipping")
        }
    }
}
