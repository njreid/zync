package dev.njr.zync.server.blob


/**
 * Content-addressed blob storage port. Keys are `blob-<sha256hex>` computed by the
 * **server** from the bytes — clients never choose the key, which keeps object names
 * traversal-safe and dedupes identical content. The server mediates all object I/O
 * so IAM stays on the instance role (clients never touch S3 directly).
 */
interface BlobStore {
    /** Store [bytes] under [key] only if absent; return true if newly written. */
    fun putIfAbsent(key: String, bytes: ByteArray): Boolean

    fun get(key: String): ByteArray?

    fun exists(key: String): Boolean
}

/** In-memory [BlobStore] for tests and small/dev deployments. */
class InMemoryBlobStore : BlobStore {
    private val objects = mutableMapOf<String, ByteArray>()

    @Synchronized
    override fun putIfAbsent(key: String, bytes: ByteArray): Boolean {
        if (objects.containsKey(key)) return false
        objects[key] = bytes.copyOf()
        return true
    }

    @Synchronized
    override fun get(key: String): ByteArray? = objects[key]?.copyOf()

    @Synchronized
    override fun exists(key: String): Boolean = objects.containsKey(key)
}

/** Compute the content-addressed key for [bytes]: `blob-<sha256hex>`. */
fun blobKey(bytes: ByteArray): String {
    return "blob-" + dev.njr.zync.server.sha256Hex(bytes)
}

/** True if [key] is a well-formed content-addressed blob key (guards path traversal). */
fun isValidBlobKey(key: String): Boolean =
    key.length == "blob-".length + 64 && key.startsWith("blob-") && key.drop(5).all { it in "0123456789abcdef" }
