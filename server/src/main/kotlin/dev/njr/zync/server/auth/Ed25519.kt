package dev.njr.zync.server.auth

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Raw Ed25519 sign/verify over BouncyCastle — native devices hold the private key
 * and sign requests; the server verifies with the registered 32-byte public key.
 */
object Ed25519 {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
        }.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            update(message, 0, message.size)
        }.generateSignature()

    /** The 32-byte public key for a 32-byte private seed. */
    fun publicKeyFor(privateKey: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    /** A fresh random 32-byte private seed. */
    fun generateSeed(): ByteArray = Ed25519PrivateKeyParameters(SecureRandom()).encoded
}
