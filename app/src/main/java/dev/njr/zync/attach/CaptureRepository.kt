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

    companion object {
        fun timestampTitle(prefix: String, now: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
            return "$prefix $stamp"
        }

        // Audio/PDF type mapping delegates to ShareImport so the two importers
        // (this one and the share-sheet one) can't drift apart, as they did
        // previously (this repository was coarsening all audio/* to .m4a while
        // ShareImport preserved mp3/ogg/wav/3gp distinctly). ShareImport.typeFor
        // returns null for anything it doesn't recognize (notably text/* and
        // extensionless/unknown MIME types), so this always-succeeds wrapper
        // falls back to its own text/filename-sniffing/PDF-default handling in
        // that case — callers here rely on a non-null result. Note ShareImport
        // also maps image/* to PDF (its own documented pragma, untouched here);
        // this repository never had a distinct image branch either, so that's
        // no behavior change.
        fun attachmentTypeFor(mimeType: String?, uri: Uri): AttachmentType =
            ShareImport.typeFor(mimeType) ?: when {
                mimeType?.startsWith("text/") == true -> AttachmentType.TRANSCRIPT
                uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true -> AttachmentType.PDF
                else -> AttachmentType.PDF
            }

        // Extension mapping delegates to ShareImport only for audio/* and PDF —
        // the confirmed drift was audio (this repository coarsened everything to
        // .m4a). Image and text extensions stay as this repository's own
        // long-standing choices (e.g. all images stored as .jpg regardless of
        // subtype), since ShareImport maps those differently and that's not a
        // confirmed drift to close.
        fun extensionFor(mimeType: String?, uri: Uri): String =
            if (mimeType != null && (mimeType.startsWith("audio/") || mimeType == "application/pdf")) {
                ShareImport.extensionFor(mimeType)
            } else {
                when {
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
