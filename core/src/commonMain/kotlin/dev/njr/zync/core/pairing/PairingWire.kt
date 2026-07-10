package dev.njr.zync.core.pairing

import kotlinx.serialization.Serializable

/** `POST /pair` body — the phone's device public key (base64) + the one-time code. */
@Serializable
data class PairRequest(val devicePublicKey: String, val code: String)

/**
 * Pairing confirmation: the assigned [deviceId], the server's public key, and a
 * signature over `zync-pair:<deviceId>:<devicePublicKey>` proving the server holds the
 * private key matching the pinned public key from the QR.
 */
@Serializable
data class PairResponse(val deviceId: String, val serverPublicKey: String, val confirmation: String)

/** The message the server signs to confirm a pairing; the phone verifies it. */
fun pairingConfirmationMessage(deviceId: String, devicePublicKeyBase64: String): ByteArray =
    "zync-pair:$deviceId:$devicePublicKeyBase64".encodeToByteArray()
