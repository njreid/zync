package dev.njr.zync.replica

import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The QR payload: where to reach the server, the key to pin, and the one-time code. */
data class PairingInvite(val address: String, val serverPublicKey: ByteArray, val code: String, val expiresAt: Long)

/** Persisted result of a successful pairing — the phone's credentials for the server. */
data class PairedServer(val address: String, val serverPublicKey: ByteArray, val deviceId: String, val deviceSeed: ByteArray)

sealed interface PairingOutcome {
    data class Paired(val server: PairedServer) : PairingOutcome
    data class Failed(val reason: String) : PairingOutcome
}

/** Where the phone persists its paired-server credentials (Keystore/prefs on Android). */
interface PairingStore {
    fun save(server: PairedServer)
    fun load(): PairedServer?
}

/** Parses a scanned `zync://pair?h=&k=&c=&e=` QR payload. */
@OptIn(ExperimentalEncodingApi::class)
object PairingUri {
    fun parse(uri: String): PairingInvite {
        require(uri.startsWith("zync://pair?")) { "not a zync pairing URI" }
        val query = uri.substringAfter('?')
        val params = query.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }
        return PairingInvite(
            address = params.getValue("h"),
            serverPublicKey = Base64.decode(params.getValue("k")),
            code = params.getValue("c"),
            expiresAt = params.getValue("e").toLong(),
        )
    }
}

/**
 * The phone half of pairing (spec `2026-07-10-device-pairing.md`): POST the device
 * public key + code, **pin** the server key (must match the QR), **verify** the
 * server-signed confirmation, and return credentials to persist. Only the device
 * public key leaves the phone.
 */
@OptIn(ExperimentalEncodingApi::class)
class PairingClient(
    private val http: HttpClient,
    private val json: Json = Json,
) {
    suspend fun pair(invite: PairingInvite, deviceSeed: ByteArray): PairingOutcome {
        val devicePublicKey = Ed25519DeviceSigner.publicKeyOf(deviceSeed)
        val devicePublicKeyB64 = Base64.encode(devicePublicKey)

        val response = http.post("${invite.address}/pair") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest(devicePublicKeyB64, invite.code)))
        }
        if (!response.status.isSuccess()) {
            return PairingOutcome.Failed("server rejected pairing (${response.status}): ${response.bodyAsText()}")
        }

        val result = json.decodeFromString(PairResponse.serializer(), response.bodyAsText())
        val serverPublicKey = Base64.decode(result.serverPublicKey)
        if (!serverPublicKey.contentEquals(invite.serverPublicKey)) {
            return PairingOutcome.Failed("server key does not match the pinned key from the QR")
        }
        val confirmed = Ed25519DeviceSigner.verify(
            serverPublicKey,
            pairingConfirmationMessage(result.deviceId, devicePublicKeyB64),
            Base64.decode(result.confirmation),
        )
        if (!confirmed) return PairingOutcome.Failed("invalid server confirmation signature")

        return PairingOutcome.Paired(PairedServer(invite.address, serverPublicKey, result.deviceId, deviceSeed))
    }
}
