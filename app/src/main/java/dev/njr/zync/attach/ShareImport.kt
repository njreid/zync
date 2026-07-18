package dev.njr.zync.attach

import dev.njr.zync.data.AttachmentType

/**
 * Pure helpers for turning an inbound `ACTION_SEND` share (from any app — a
 * voice recorder, a document scanner, Drive, Files, …) into an attachment we
 * store. Kept free of Android framework types so the mapping is unit-testable;
 * the Activity glue that reads the actual bytes lives in
 * [ShareReceiverActivity].
 *
 * Accepted: audio (voice notes), PDFs (scanned documents), images (typed PDF —
 * the same pragma as the camera path until AttachmentType grows an IMAGE), and
 * shared text/URLs (handled upstream as note captures, not attachments).
 * Everything else is rejected so a stray share can't create junk Inbox items.
 */
object ShareImport {

    /** The attachment type we store a given MIME type as, or null if unsupported. */
    fun typeFor(mime: String?): AttachmentType? = when {
        mime == null -> null
        mime.startsWith("audio/") -> AttachmentType.AUDIO
        mime == "application/pdf" -> AttachmentType.PDF
        mime.startsWith("image/") -> AttachmentType.PDF
        else -> null
    }

    /** File extension to store a blob under, derived from its MIME subtype. */
    fun extensionFor(mime: String?): String = when {
        mime == "application/pdf" -> "pdf"
        mime != null && mime.startsWith("image/") -> when (mime.substringAfter('/').substringBefore(';')) {
            "jpeg" -> "jpg"
            else -> mime.substringAfter('/').substringBefore(';').ifBlank { "bin" }
        }
        mime != null && mime.startsWith("audio/") -> when (val sub = mime.substringAfter('/')) {
            "mpeg", "mp3" -> "mp3"
            "mp4", "m4a", "aac", "x-m4a" -> "m4a"
            "ogg", "opus" -> "ogg"
            "wav", "x-wav" -> "wav"
            "3gpp", "amr" -> "3gp"
            else -> sub.substringBefore(';').ifBlank { "bin" }
        }
        else -> "bin"
    }

    /** Default Inbox title for a captured share (the user renames it in Clarify). */
    fun titleFor(type: AttachmentType, mime: String? = null): String = when {
        mime != null && mime.startsWith("image/") -> "Shared image"
        type == AttachmentType.AUDIO -> "Voice note"
        type == AttachmentType.PDF -> "Scanned document"
        type == AttachmentType.TRANSCRIPT -> "Transcript"
        else -> "Scanned text"
    }

    /** The first URL in a shared text, if any. */
    fun firstUrl(text: String): String? = URL_RE.find(text)?.value

    /** Inbox title for a shared text/URL: the subject, else a compact host/path. */
    fun titleForText(subject: String?, text: String): String {
        subject?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(120) }
        val url = firstUrl(text)
        if (url != null) {
            val compact = url.substringAfter("://").removePrefix("www.").substringBefore('?').trimEnd('/')
            return compact.take(80)
        }
        return text.trim().take(80).ifBlank { "Shared text" }
    }

    private val URL_RE = Regex("https?://\\S+")
}
