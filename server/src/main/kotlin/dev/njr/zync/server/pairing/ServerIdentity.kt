package dev.njr.zync.server.pairing

import dev.njr.zync.server.auth.Ed25519
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The server's long-lived Ed25519 identity. Its public key is printed in the pairing
 * QR and pinned by the phone; the server signs the pairing confirmation with the
 * private seed so the phone can verify it's talking to the genuine server (defense in
 * depth beyond TLS, which survives Let's Encrypt cert rotation).
 */
@OptIn(ExperimentalEncodingApi::class)
class ServerIdentity private constructor(private val privateSeed: ByteArray) {
    val publicKey: ByteArray = Ed25519.publicKeyFor(privateSeed)
    val publicKeyBase64: String = Base64.encode(publicKey)

    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateSeed, message)

    companion object {
        fun fromSeed(seed: ByteArray) = ServerIdentity(seed)

        /** Load the identity from [keyFile] (base64 seed), creating + persisting it if absent. */
        fun loadOrCreate(keyFile: String): ServerIdentity {
            val file = File(keyFile)
            if (file.exists()) return ServerIdentity(Base64.decode(file.readText().trim()))
            val seed = Ed25519.generateSeed()
            file.parentFile?.mkdirs()
            file.writeText(Base64.encode(seed))
            return ServerIdentity(seed)
        }
    }
}
