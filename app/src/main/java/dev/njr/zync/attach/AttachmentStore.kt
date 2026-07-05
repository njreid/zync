package dev.njr.zync.attach

import android.content.Context
import dev.njr.zync.data.AttachmentType
import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed file storage for attachment blobs (voice recordings,
 * scanned PDFs, and their derived transcript/OCR text). Files live under a
 * single portable root directory; each blob's path is derived from a SHA-256
 * of its bytes, so identical content is stored once (dedup) and paths are
 * stable/verifiable.
 *
 * The [AttachmentEntity.relativePath][dev.njr.zync.data.AttachmentEntity] stored
 * in the DB is exactly the value returned by [write] — relative to [root], so
 * the whole folder is portable and can be backed up wholesale (see the M2
 * backup task).
 *
 * `root` is injected so the store is unit-testable against a temp dir; use
 * [default] for the on-device location.
 */
class AttachmentStore(val root: File) {

    /**
     * Store [bytes] and return the DB-relative path. Layout:
     * `<type>/<hash[0:2]>/<hash>.<extension>`. Writing the same bytes twice is
     * a no-op (the first write wins), so callers can retry safely.
     */
    fun write(bytes: ByteArray, type: AttachmentType, extension: String): String {
        val hash = sha256Hex(bytes)
        val ext = extension.trimStart('.')
        val relativePath = "${type.name.lowercase()}/${hash.substring(0, 2)}/$hash.$ext"
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            // Write to a temp sibling then rename, so a crash mid-write never
            // leaves a partial file at the content-addressed (assumed-complete)
            // path.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        return relativePath
    }

    /** Absolute file for a stored [relativePath] (may not exist). */
    fun resolve(relativePath: String): File = File(root, relativePath)

    /** Read a stored blob, or null if it's missing. */
    fun read(relativePath: String): ByteArray? =
        resolve(relativePath).takeIf { it.isFile }?.readBytes()

    /** Delete a stored blob (best effort). Returns true if a file was removed. */
    fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * On-device store root. Uses app-specific external storage
         * (`<external>/Zync`, no runtime permission required) so captures work
         * out of the box; the M2 backup task syncs this whole tree to Drive.
         */
        fun default(context: Context): AttachmentStore {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return AttachmentStore(File(base, "Zync"))
        }
    }
}
