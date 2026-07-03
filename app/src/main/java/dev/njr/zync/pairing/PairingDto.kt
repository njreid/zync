package dev.njr.zync.pairing

import kotlinx.serialization.Serializable

/**
 * Payload encoded into the QR code shown by the desktop during pairing. The phone camera scans
 * this; there is no other channel involved, so the [nonce] is what binds this payload to the
 * specific `beginPairing()` call it answers.
 */
@Serializable
data class QrPayload(
    val devicePubkey: String,
    val deviceName: String,
    val nonce: String,
)

/** Body of the desktop's polled "have I been approved yet" request. */
@Serializable
data class PairRequestBody(
    val devicePubkey: String,
    val nonce: String,
)

/** Returned to the desktop once pairing completes. */
@Serializable
data class PairResultDto(
    val certFingerprint: String,
    val confirmCode: String,
)

/** A server-issued, single-use challenge for the session challenge-response handshake. */
@Serializable
data class ChallengeDto(
    val challenge: String,
)

/** Desktop's proof of possession of the paired device's private key. */
@Serializable
data class SessionRequestBody(
    val devicePubkey: String,
    val challenge: String,
    val signature: String,
)

/** Opaque bearer token for the sync session. */
@Serializable
data class SessionDto(
    val token: String,
)
