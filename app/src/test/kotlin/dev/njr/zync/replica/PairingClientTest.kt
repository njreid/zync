package dev.njr.zync.replica

import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class PairingClientTest {
    private val json = Json
    private val serverSeed = ByteArray(32) { (it + 7).toByte() }
    private val serverPub = Ed25519DeviceSigner.publicKeyOf(serverSeed)
    private val serverSigner = Ed25519DeviceSigner("server", serverSeed)
    private val deviceSeed = Ed25519DeviceSigner.generateSeed()
    private val validCode = "ABC123"

    private fun invite(pinnedKey: ByteArray = serverPub) =
        PairingInvite("https://srv", pinnedKey, validCode, expiresAt = 9_999_999_999)

    /** Fake /pair: validates the code and signs the confirmation with [signWith], returning [returnKey]. */
    private fun fakeServer(signWith: Ed25519DeviceSigner = serverSigner, returnKey: ByteArray = serverPub) =
        HttpClient(MockEngine { request ->
            val req = json.decodeFromString(PairRequest.serializer(), request.body.toByteArray().decodeToString())
            if (req.code != validCode) {
                respond("unknown or expired code", HttpStatusCode.Unauthorized)
            } else {
                val deviceId = "device-abc"
                val confirmation = signWith.sign(pairingConfirmationMessage(deviceId, req.devicePublicKey))
                val body = json.encodeToString(PairResponse.serializer(), PairResponse(deviceId, Base64.encode(returnKey), Base64.encode(confirmation)))
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        })

    @Test
    fun pairFromUriPairsAndPersists() = runBlocking {
        val store = object : PairingStore {
            var saved: PairedServer? = null
            override fun save(server: PairedServer) { saved = server }
            override fun load(): PairedServer? = saved
        }
        val k = URLEncoder.encode(Base64.encode(serverPub), Charsets.UTF_8)
        val uri = "zync://pair?h=${URLEncoder.encode("https://srv", Charsets.UTF_8)}&k=$k&c=$validCode&e=9999999999"

        val outcome = pairFromUri(fakeServer(), uri, replicaId = "replica-1", store = store)

        assertTrue(outcome is PairingOutcome.Paired)
        assertEquals("device-abc", store.saved?.deviceId)
        assertEquals("https://srv", store.saved?.address)

        // Not a pairing link → fails without touching the store or the network.
        val bad = pairFromUri(fakeServer(), "https://phish.example/pair", replicaId = "replica-1", store = store)
        assertTrue(bad is PairingOutcome.Failed)
    }

    @Test
    fun parsesPairingUri() {
        val k = URLEncoder.encode(Base64.encode(serverPub), Charsets.UTF_8)
        val uri = "zync://pair?h=${URLEncoder.encode("https://srv", Charsets.UTF_8)}&k=$k&c=ABC123&e=12345"
        val parsed = PairingUri.parse(uri)
        assertEquals("https://srv", parsed.address)
        assertTrue(parsed.serverPublicKey.contentEquals(serverPub))
        assertEquals("ABC123", parsed.code)
        assertEquals(12345L, parsed.expiresAt)
    }

    @Test
    fun successfulPairingReturnsPinnedCredentials() = runBlocking {
        val outcome = PairingClient(fakeServer()).pair(invite(), deviceSeed, replicaId = "replica-1")
        assertTrue(outcome is PairingOutcome.Paired); val paired = outcome as PairingOutcome.Paired
        assertEquals("device-abc", paired.server.deviceId)
        assertTrue(paired.server.serverPublicKey.contentEquals(serverPub))
        assertTrue(paired.server.deviceSeed.contentEquals(deviceSeed))
    }

    @Test
    fun wrongCodeIsRejected() = runBlocking {
        val outcome = PairingClient(fakeServer()).pair(invite().copy(code = "NOPE"), deviceSeed, replicaId = "replica-1")
        assertTrue(outcome is PairingOutcome.Failed)
    }

    @Test
    fun serverKeyMismatchIsRejected() = runBlocking {
        // server returns a different key than the one pinned in the QR
        val otherKey = Ed25519DeviceSigner.publicKeyOf(ByteArray(32) { (it + 50).toByte() })
        val outcome = PairingClient(fakeServer(returnKey = otherKey)).pair(invite(), deviceSeed, replicaId = "replica-1")
        assertTrue(outcome is PairingOutcome.Failed)
    }

    @Test
    fun badConfirmationSignatureIsRejected() = runBlocking {
        // confirmation signed by the wrong key
        val impostor = Ed25519DeviceSigner("impostor", ByteArray(32) { (it + 90).toByte() })
        val outcome = PairingClient(fakeServer(signWith = impostor)).pair(invite(), deviceSeed, replicaId = "replica-1")
        assertTrue(outcome is PairingOutcome.Failed)
    }
}
