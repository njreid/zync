package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.auth.Ed25519
import dev.njr.zync.server.auth.NonceCache
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.auth.SignedRequestVerifier
import dev.njr.zync.server.auth.SqlDeviceRegistry
import dev.njr.zync.server.auth.ZyncAuthenticator
import dev.njr.zync.core.pairing.PairRequest
import dev.njr.zync.core.pairing.PairResponse
import dev.njr.zync.server.pairing.PairingEndpoint
import dev.njr.zync.server.pairing.PairingManager
import dev.njr.zync.server.pairing.ServerIdentity
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class PairingRoutesTest {
    private val json = Json
    private val now = 10_000L
    private val deviceSeed = ByteArray(32) { (it + 5).toByte() }
    private val devicePub = Ed25519.publicKeyFor(deviceSeed)

    @Test
    fun pairThenSignedRequestIsAccepted() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val registry = SqlDeviceRegistry(db)
        val manager = PairingManager(db, registry)
        val identity = ServerIdentity.fromSeed(ByteArray(32) { (it + 99).toByte() })
        val verifier = SignedRequestVerifier(registry, NonceCache(300_000), windowMillis = 300_000)
        val auth = ServerAuth(ZyncAuthenticator(verifier, SessionStore(), now = { now }), null)
        application {
            zyncModule(SyncService(db), auth = auth, pairing = PairingEndpoint(manager, identity))
        }

        // operator mints a code (as `zync pair` would). Pairing uses the real system
        // clock (route default); the device-auth `now` above is a separate fixed clock.
        val code = manager.open(System.currentTimeMillis())

        // phone redeems it
        val pairBody = client.post("/pair") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest(Base64.encode(devicePub), code)))
        }.bodyAsText()
        val paired = json.decodeFromString(PairResponse.serializer(), pairBody)

        // the confirmation proves the server holds the private key for the pinned pubkey
        val serverPub = Base64.decode(paired.serverPublicKey)
        assertTrue(
            Ed25519.verify(serverPub, pairingConfirmationMessage(paired.deviceId, Base64.encode(devicePub)), Base64.decode(paired.confirmation)),
            "server confirmation must verify against its public key",
        )

        // now the phone can make signed requests with its device key
        val sig = Base64.encode(Ed25519.sign(deviceSeed, SignedRequestVerifier.canonicalString("GET", "/sync/pull", now, "n1").encodeToByteArray()))
        val response = client.get("/sync/pull?since=0") {
            header("X-Device-Id", paired.deviceId)
            header("X-Timestamp", now.toString())
            header("X-Nonce", "n1")
            header("X-Signature", sig)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun invalidCodeIsRejectedAndDeviceStaysUnauthorized() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val registry = SqlDeviceRegistry(db)
        val manager = PairingManager(db, registry)
        val identity = ServerIdentity.fromSeed(ByteArray(32) { (it + 99).toByte() })
        val verifier = SignedRequestVerifier(registry, NonceCache(300_000), windowMillis = 300_000)
        val auth = ServerAuth(ZyncAuthenticator(verifier, SessionStore(), now = { now }), null)
        application {
            zyncModule(SyncService(db), auth = auth, pairing = PairingEndpoint(manager, identity))
        }

        val pair = client.post("/pair") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest(Base64.encode(devicePub), "WRONGCODE")))
        }
        assertEquals(HttpStatusCode.Unauthorized, pair.status)

        // unpaired device's signed request is still rejected
        val sig = Base64.encode(Ed25519.sign(deviceSeed, SignedRequestVerifier.canonicalString("GET", "/sync/pull", now, "n2").encodeToByteArray()))
        val response = client.get("/sync/pull?since=0") {
            header("X-Device-Id", PairingManager.fingerprint(devicePub))
            header("X-Timestamp", now.toString())
            header("X-Nonce", "n2")
            header("X-Signature", sig)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
