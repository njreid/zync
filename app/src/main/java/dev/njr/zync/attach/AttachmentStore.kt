package dev.njr.zync.attach

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest

class AttachmentStore(private val root: File) {
    init {
        root.mkdirs()
    }

    fun writeContent(bytes: ByteArray, extension: String): String {
        val cleanExtension = extension.trimStart('.')
        require(cleanExtension.isNotBlank()) { "extension required" }
        require(cleanExtension.all { it.isLetterOrDigit() }) { "invalid extension" }

        val hash = sha256(bytes)
        val relativePath = "attachments/${hash.take(2)}/$hash.$cleanExtension"
        write(relativePath, bytes)
        return relativePath
    }

    fun write(relativePath: String, bytes: ByteArray): File {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    fun read(relativePath: String): ByteArray = resolve(relativePath).readBytes()

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
        fun defaultRoot(context: Context): File {
            val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
            return File(documents, "Zync")
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
