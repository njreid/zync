package dev.njr.zync.backup

/**
 * A remote store for encrypted backup data, split into two namespaces:
 *
 *  - **blobs** — content-addressed (key = SHA-256 hex of the *plaintext*), so
 *    unchanged DB/attachment content is stored once and shared across
 *    snapshots (cheap incrementals).
 *  - **manifests** — one per snapshot; the id sorts chronologically. A manifest
 *    (encrypted JSON) references the blob keys that make up that snapshot.
 *
 * All bytes handed to/returned from this store are already AES-GCM encrypted;
 * the store itself is oblivious to plaintext. The production implementation is
 * Google Drive (`appDataFolder`); tests use an in-memory fake.
 */
interface BackupStore {
    suspend fun hasBlob(key: String): Boolean
    suspend fun putBlob(key: String, bytes: ByteArray)
    suspend fun getBlob(key: String): ByteArray?
    suspend fun listBlobKeys(): List<String>
    suspend fun deleteBlob(key: String)

    suspend fun putManifest(id: String, bytes: ByteArray)
    suspend fun getManifest(id: String): ByteArray?
    /** Manifest ids in ascending (chronological) order. */
    suspend fun listManifests(): List<String>
    suspend fun deleteManifest(id: String)
}
