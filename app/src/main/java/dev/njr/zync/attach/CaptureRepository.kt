package dev.njr.zync.attach

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.njr.zync.ZyncApp
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.data.AttachmentType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CaptureRepository(private val app: ZyncApp) {
    private val resolver: ContentResolver = app.contentResolver

    suspend fun importBytes(
        title: String,
        type: AttachmentType,
        bytes: ByteArray,
        extension: String,
    ): Ulid = withContext(Dispatchers.IO) {
        app.captureToInbox(title, type, bytes, extension)
    }

    suspend fun importUri(uri: Uri, fallbackTitle: String? = null): Ulid = withContext(Dispatchers.IO) {
        val mimeType = resolver.getType(uri)
        val type = attachmentTypeFor(mimeType, uri)
        val extension = extensionFor(mimeType, uri)
        val title = fallbackTitle ?: displayName(uri)?.substringBeforeLast('.') ?: defaultTitle(type)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot read shared file")
        importBytes(title, type, bytes, extension)
    }

    suspend fun importSharedText(text: String): Ulid = withContext(Dispatchers.IO) {
        val title = text.lineSequence().firstOrNull()?.take(120) ?: "Shared text"
        app.replicaCapture.captureNote(title).also { app.contentChanges.notifyChanged() }
    }

    companion object {
        fun timestampTitle(prefix: String, now: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
            return "$prefix $stamp"
        }

        fun attachmentTypeFor(mimeType: String?, uri: Uri): AttachmentType =
            when {
                mimeType?.startsWith("audio/") == true -> AttachmentType.AUDIO
                mimeType == "application/pdf" -> AttachmentType.PDF
                mimeType?.startsWith("text/") == true -> AttachmentType.TRANSCRIPT
                uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true -> AttachmentType.PDF
                else -> AttachmentType.PDF
            }

        fun extensionFor(mimeType: String?, uri: Uri): String =
            when {
                mimeType == "audio/mp4" || mimeType == "audio/m4a" -> "m4a"
                mimeType?.startsWith("audio/") == true -> "m4a"
                mimeType == "application/pdf" -> "pdf"
                mimeType?.startsWith("image/") == true -> "jpg"
                mimeType?.startsWith("text/") == true -> "txt"
                uri.lastPathSegment?.contains('.') == true ->
                    uri.lastPathSegment!!
                        .substringAfterLast('.')
                        .filter { it.isLetterOrDigit() }
                        .lowercase()
                        .takeIf { it.isNotBlank() && it.length <= 8 }
                        ?: "bin"
                else -> "bin"
            }

        private fun defaultTitle(type: AttachmentType): String =
            when (type) {
                AttachmentType.AUDIO -> timestampTitle("Voice note")
                AttachmentType.PDF -> timestampTitle("Scan")
                AttachmentType.TRANSCRIPT -> timestampTitle("Transcript")
                AttachmentType.OCR_TEXT -> timestampTitle("OCR text")
            }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
