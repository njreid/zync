package dev.njr.zync.attach

import android.content.Context
import android.os.Environment
import dev.njr.zync.data.AttachmentType
import java.io.File
import java.security.MessageDigest

class AttachmentStore(val root: File) {
    init {
        root.mkdirs()
    }

    fun write(bytes: ByteArray, type: AttachmentType, extension: String): String {
        val cleanExtension = cleanExtension(extension)
        val hash = sha256(bytes)
        val relativePath = "${type.name.lowercase()}/${hash.take(2)}/$hash.$cleanExtension"
        write(relativePath, bytes)
        return relativePath
    }

    fun writeContent(bytes: ByteArray, extension: String): String {
        val cleanExtension = cleanExtension(extension)
        val hash = sha256(bytes)
        val relativePath = "attachments/${hash.take(2)}/$hash.$cleanExtension"
        write(relativePath, bytes)
        return relativePath
    }

    fun write(relativePath: String, bytes: ByteArray): File {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        return file
    }

    fun read(relativePath: String): ByteArray? =
        resolve(relativePath).takeIf { it.isFile }?.readBytes()

    fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    fun resolve(relativePath: String): File {
        require(relativePath.isNotBlank()) { "relativePath required" }
        val candidate = File(relativePath)
        require(!candidate.isAbsolute) { "relativePath must be relative" }

        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, relativePath).canonicalFile
        require(resolved.path == canonicalRoot.path || resolved.path.startsWith(canonicalRoot.path + File.separator)) {
            "relativePath escapes attachment root"
        }
        return resolved
    }

    companion object {
        fun default(context: Context): AttachmentStore = AttachmentStore(defaultRoot(context))

        fun defaultRoot(context: Context): File {
            val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
            return File(documents, "Zync")
        }

        private fun cleanExtension(extension: String): String {
            val clean = extension.trimStart('.')
            require(clean.isNotBlank()) { "extension required" }
            require(clean.all { it.isLetterOrDigit() }) { "invalid extension" }
            return clean
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
