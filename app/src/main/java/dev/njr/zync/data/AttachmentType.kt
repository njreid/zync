package dev.njr.zync.data

/**
 * The kind of blob a capture attaches to an inbox node. Independent of any storage
 * layer — the live op-log capture path (`ReplicaCapture`/`ZyncApp.captureToInbox`)
 * carries it as `type.name.lowercase()`.
 */
enum class AttachmentType { AUDIO, TRANSCRIPT, PDF, OCR_TEXT }
