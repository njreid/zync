package dev.njr.zync.replica

import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Builds the device-auth headers for a request — the canonical
 * `method\npath\nquery\ntimestamp\nnonce\nsha256(body)` signed with the device key
 * (matches the server's `SignedRequestVerifier`, so the signature authorizes this
 * exact payload). Shared by the sync client and the blob uploader. [query] is the
 * raw query string exactly as sent (no leading `?`); empty when there is none.
 */
@OptIn(ExperimentalEncodingApi::class)
fun signedHeaders(
    signer: DeviceSigner,
    method: String,
    path: String,
    timestamp: Long,
    nonce: String,
    query: String = "",
    body: ByteArray = ByteArray(0),
): Map<String, String> {
    val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    val canonical = "$method\n$path\n$query\n$timestamp\n$nonce\n$bodyHash"
    return mapOf(
        "X-Device-Id" to signer.deviceId,
        "X-Timestamp" to timestamp.toString(),
        "X-Nonce" to nonce,
        "X-Signature" to Base64.encode(signer.sign(canonical.encodeToByteArray())),
    )
}
