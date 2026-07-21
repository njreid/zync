package dev.njr.zync.replica

/**
 * OCR transport for scanned/photo documents. The phone owns this leg because the
 * Google account lives on the device (no server-held Google credentials). Bytes
 * transit Google (Drive converts the upload to a Doc to OCR it), then the transient
 * Doc is deleted in the same call — nothing accumulates in Drive.
 *
 * The production implementation is [GoogleDriveOcr] (device-verified); tests drive
 * [OcrProcessor] against a fake.
 */
interface DriveOcr {
    /**
     * Extract plain text from [bytes] (a PDF or image of [sourceMime]). Returns the
     * OCR text on success; throws [DriveOcrException] on failure so the caller can
     * distinguish a retryable hiccup from a permanent one.
     */
    suspend fun ocr(bytes: ByteArray, sourceMime: String): String
}

/**
 * A Drive-OCR failure. [permanent] failures (unsupported content, revoked consent,
 * quota) mark the scan FAILED and are not retried; transient ones (network, 5xx,
 * token refresh) leave the work to WorkManager's backoff.
 */
class DriveOcrException(
    val permanent: Boolean,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
