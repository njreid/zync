package dev.njr.zync.backup

import dev.njr.zync.data.AttachmentEntity
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Point-in-time, content-addressed, encrypted snapshot backup of all zync state
 * (the `zync.db` snapshot + every attachment file).
 *
 * Each [backUp] writes a new [SnapshotManifest] plus any not-yet-stored blobs,
 * so unchanged content is never re-uploaded (cheap incrementals) yet every
 * snapshot is independently restorable. [retain] snapshots are kept; older ones
 * are pruned and their now-unreferenced blobs garbage-collected.
 *
 * Everything written to [store] is AES-GCM encrypted with [key]; the store only
 * ever holds ciphertext.
 *
 * This class is deliberately free of Android/WorkManager/Drive types so the
 * whole snapshot/restore/retention behaviour is unit-testable against an
 * in-memory [BackupStore]; scheduling and the real Drive store are the device
 * layer.
 */
class SnapshotBackup(
    private val store: BackupStore,
    private val key: ByteArray,
    private val retain: Int = DEFAULT_RETAIN,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true }

    /**
     * Capture a new snapshot from [dbFile] (a consistent DB snapshot — the
     * caller checkpoints WAL first) and the [attachments] rows under
     * [attachmentsRoot]. Returns the new snapshot id.
     */
    suspend fun backUp(
        dbFile: File,
        attachmentsRoot: File,
        attachments: List<AttachmentEntity>,
    ): String {
        val dbBlobKey = putBlobIfAbsent(dbFile.readBytes())
        val refs = attachments.mapNotNull { a ->
            val file = File(attachmentsRoot, a.relativePath)
            if (!file.isFile) return@mapNotNull null
            AttachmentRef(a.relativePath, putBlobIfAbsent(file.readBytes()))
        }
        val manifest = SnapshotManifest(createdAt = now(), dbBlobKey = dbBlobKey, attachments = refs)
        val id = manifestId(manifest.createdAt)
        store.putManifest(id, BackupCrypto.encrypt(json.encodeToString(manifest).toByteArray(), key))
        prune()
        return id
    }

    /** Ids of all retained snapshots, oldest first. */
    suspend fun snapshots(): List<String> = store.listManifests()

    /**
     * Restore a snapshot (the latest if [id] is null) into [targetDbFile] and
     * [targetAttachmentsRoot], overwriting them. Returns false if there is no
     * such snapshot.
     */
    suspend fun restore(
        id: String?,
        targetDbFile: File,
        targetAttachmentsRoot: File,
    ): Boolean {
        val snapshotId = id ?: store.listManifests().lastOrNull() ?: return false
        val manifest = readManifest(snapshotId) ?: return false

        val dbBytes = readBlob(manifest.dbBlobKey) ?: return false
        targetDbFile.parentFile?.mkdirs()
        targetDbFile.writeBytes(dbBytes)

        for (ref in manifest.attachments) {
            val bytes = readBlob(ref.blobKey) ?: continue
            val out = File(targetAttachmentsRoot, ref.relativePath)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }
        return true
    }

    private suspend fun putBlobIfAbsent(plaintext: ByteArray): String {
        val blobKey = sha256Hex(plaintext)
        if (!store.hasBlob(blobKey)) {
            store.putBlob(blobKey, BackupCrypto.encrypt(plaintext, key))
        }
        return blobKey
    }

    private suspend fun readManifest(id: String): SnapshotManifest? {
        val enc = store.getManifest(id) ?: return null
        return json.decodeFromString<SnapshotManifest>(String(BackupCrypto.decrypt(enc, key)))
    }

    private suspend fun readBlob(blobKey: String): ByteArray? =
        store.getBlob(blobKey)?.let { BackupCrypto.decrypt(it, key) }

    /** Drop snapshots beyond [retain] (oldest first) and GC orphaned blobs. */
    private suspend fun prune() {
        val all = store.listManifests()
        val toDelete = if (all.size > retain) all.subList(0, all.size - retain).toList() else emptyList()
        toDelete.forEach { store.deleteManifest(it) }

        val referenced = HashSet<String>()
        for (id in store.listManifests()) {
            val manifest = readManifest(id) ?: continue
            referenced += manifest.dbBlobKey
            manifest.attachments.forEach { referenced += it.blobKey }
        }
        for (blobKey in store.listBlobKeys()) {
            if (blobKey !in referenced) store.deleteBlob(blobKey)
        }
    }

    private fun manifestId(createdAt: Long): String = "snap-%020d".format(createdAt)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_RETAIN = 10
    }
}
