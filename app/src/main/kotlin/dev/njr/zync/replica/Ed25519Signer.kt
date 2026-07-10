package dev.njr.zync.replica

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Signs requests with the device's Ed25519 key. */
interface DeviceSigner {
    val deviceId: String
    fun sign(message: ByteArray): ByteArray
}

/**
 * BouncyCastle Ed25519 signer over a raw 32-byte private seed. Used by the sync client
 * and pairing; the private seed is produced/held by the pairing flow (Task 4). Only the
 * public key ever leaves the device.
 */
class Ed25519DeviceSigner(
    override val deviceId: String,
    private val privateSeed: ByteArray,
) : DeviceSigner {
    override fun sign(message: ByteArray): ByteArray =
        Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
            update(message, 0, message.size)
        }.generateSignature()

    companion object {
        fun publicKeyOf(privateSeed: ByteArray): ByteArray =
            Ed25519PrivateKeyParameters(privateSeed, 0).generatePublicKey().encoded
    }
}
