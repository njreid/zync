package dev.njr.zync.server.blob

/** Raised when an upload exceeds [BlobService.maxBytes]. */
class BlobTooLargeException(val size: Int, val max: Long) :
    RuntimeException("blob of $size bytes exceeds limit of $max")

/**
 * Application logic over a [BlobStore]: computes the content-addressed key from the
 * bytes (client can't choose it), enforces a size cap, and dedupes via putIfAbsent.
 * Ties to `AddAttachment` ops, whose `blobHash` is this key.
 */
class BlobService(
    private val store: BlobStore,
    val maxBytes: Long = 25L * 1024 * 1024,
) {
    /** Store [bytes] (deduped by content); return the content-addressed key. */
    fun store(bytes: ByteArray): String {
        if (bytes.size > maxBytes) throw BlobTooLargeException(bytes.size, maxBytes)
        val key = blobKey(bytes)
        store.putIfAbsent(key, bytes)
        return key
    }

    /** Fetch bytes for a valid [key]; null if the key is malformed or absent. */
    fun fetch(key: String): ByteArray? {
        if (!isValidBlobKey(key)) return null
        return store.get(key)
    }

    /** True iff [key] is well-formed and present — a metadata check (S3 HEAD), no download. */
    fun exists(key: String): Boolean = isValidBlobKey(key) && store.exists(key)
}
