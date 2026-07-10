package dev.njr.zync.replica

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Builds the device-auth headers for a request — the canonical
 * `method\npath\ntimestamp\nnonce` signed with the device key (matches the server's
 * `SignedRequestVerifier`). Shared by the sync client and the blob uploader.
 */
@OptIn(ExperimentalEncodingApi::class)
fun signedHeaders(signer: DeviceSigner, method: String, path: String, timestamp: Long, nonce: String): Map<String, String> {
    val canonical = "$method\n$path\n$timestamp\n$nonce"
    return mapOf(
        "X-Device-Id" to signer.deviceId,
        "X-Timestamp" to timestamp.toString(),
        "X-Nonce" to nonce,
        "X-Signature" to Base64.encode(signer.sign(canonical.encodeToByteArray())),
    )
}
