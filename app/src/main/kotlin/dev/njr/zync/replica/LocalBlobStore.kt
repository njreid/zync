package dev.njr.zync.replica

import java.io.File
import java.security.MessageDigest

/** Content-addressed key `blob-<sha256>` — identical to the server's, so hashes line up. */
fun blobKeyOf(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return "blob-" + digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/**
 * On-device content-addressed blob store (attachment bytes). Files are named by their
 * key under [dir]; `put` is idempotent. Bytes upload to the server `/blob` on sync
 * ([BlobUploader]); the `AddAttachment` op carries the hash.
 */
class LocalBlobStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun put(bytes: ByteArray): String {
        val key = blobKeyOf(bytes)
        val file = File(dir, key)
        if (!file.exists()) file.writeBytes(bytes)
        return key
    }

    fun get(key: String): ByteArray? = File(dir, key).let { if (it.exists()) it.readBytes() else null }

    fun has(key: String): Boolean = File(dir, key).exists()
}
