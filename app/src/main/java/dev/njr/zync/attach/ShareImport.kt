package dev.njr.zync.attach

import dev.njr.zync.data.AttachmentType

/**
 * Pure helpers for turning an inbound `ACTION_SEND` share (from any app — a
 * voice recorder, a document scanner, Drive, Files, …) into an attachment we
 * store. Kept free of Android framework types so the mapping is unit-testable;
 * the Activity glue that reads the actual bytes lives in
 * [ShareReceiverActivity].
 *
 * We accept exactly the two things the Inbox capture story is about: audio
 * (voice notes) and PDFs (scanned documents). Everything else is rejected so a
 * stray share can't create junk Inbox items.
 */
object ShareImport {

    /** The attachment type we store a given MIME type as, or null if unsupported. */
    fun typeFor(mime: String?): AttachmentType? = when {
        mime == null -> null
        mime.startsWith("audio/") -> AttachmentType.AUDIO
        mime == "application/pdf" -> AttachmentType.PDF
        else -> null
    }

    /** File extension to store a blob under, derived from its MIME subtype. */
    fun extensionFor(mime: String?): String = when {
        mime == "application/pdf" -> "pdf"
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
    fun titleFor(type: AttachmentType): String = when (type) {
        AttachmentType.AUDIO -> "Voice note"
        AttachmentType.PDF -> "Scanned document"
        AttachmentType.TRANSCRIPT -> "Transcript"
        AttachmentType.OCR_TEXT -> "Scanned text"
    }
}
