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
import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.server.pairing.PairingEndpoint
import dev.njr.zync.server.pairing.PairingManager
import dev.njr.zync.server.pairing.ServerIdentity
import dev.njr.zync.core.pairing.pairingConfirmationMessage
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
    private val emptyBodyHash = sha256Hex(ByteArray(0))

    private class Harness(val db: dev.njr.zync.data.db.ZyncDatabase) {
        val registry = SqlDeviceRegistry(db)
        val manager = PairingManager(db, registry)
        val identity = ServerIdentity.fromSeed(ByteArray(32) { (it + 99).toByte() })
    }

    private fun harness() = Harness(JvmZyncDatabase.inMemory())

    private fun Harness.auth(now: Long) = ServerAuth(
        ZyncAuthenticator(SignedRequestVerifier(registry, NonceCache(300_000), windowMillis = 300_000), SessionStore(), now = { now }),
        null,
        replicaIdOf = registry::replicaId,
    )

    private fun sign(method: String, path: String, nonce: String, query: String = "", body: ByteArray = ByteArray(0)): String {
        val canonical = SignedRequestVerifier.canonicalString(method, path, query, now, nonce, sha256Hex(body))
        return Base64.encode(Ed25519.sign(deviceSeed, canonical.encodeToByteArray()))
    }

    private suspend fun HttpClient.pair(code: String, replicaId: String): HttpResponse = post("/pair") {
        header(HttpHeaders.ContentType, "application/json")
        setBody(json.encodeToString(PairRequest.serializer(), PairRequest(Base64.encode(devicePub), code, replicaId)))
    }

    private suspend fun HttpClient.signedPush(deviceId: String, nonce: String, ops: PushRequest): HttpResponse {
        val body = json.encodeToString(PushRequest.serializer(), ops).encodeToByteArray()
        return post("/sync/push") {
            header(HttpHeaders.ContentType, "application/json")
            header("X-Device-Id", deviceId)
            header("X-Timestamp", now.toString())
            header("X-Nonce", nonce)
            header("X-Signature", sign("POST", "/sync/push", nonce, body = body))
            setBody(body)
        }
    }

    @Test
    fun pairThenSignedRequestIsAccepted() = testApplication {
        val h = harness()
        application {
            zyncModule(SyncService(h.db), auth = h.auth(now), pairing = PairingEndpoint(h.manager, h.identity))
        }

        // operator mints a code (as `zync pair` would). Pairing uses the real system
        // clock (route default); the device-auth `now` above is a separate fixed clock.
        val code = h.manager.open(System.currentTimeMillis())

        // phone redeems it, declaring its op-authoring replica id
        val paired = json.decodeFromString(PairResponse.serializer(), client.pair(code, "replica-A").bodyAsText())

        // the confirmation proves the server holds the private key for the pinned pubkey
        val serverPub = Base64.decode(paired.serverPublicKey)
        assertTrue(
            Ed25519.verify(serverPub, pairingConfirmationMessage(paired.deviceId, Base64.encode(devicePub)), Base64.decode(paired.confirmation)),
            "server confirmation must verify against its public key",
        )

        // now the phone can make signed requests with its device key
        val response = client.get("/sync/pull?since=0") {
            header("X-Device-Id", paired.deviceId)
            header("X-Timestamp", now.toString())
            header("X-Nonce", "n1")
            header("X-Signature", sign("GET", "/sync/pull", "n1", query = "since=0"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun pushIsBoundToThePairedReplicaId() = testApplication {
        val h = harness()
        application {
            zyncModule(SyncService(h.db), auth = h.auth(now), pairing = PairingEndpoint(h.manager, h.identity))
        }
        val code = h.manager.open(System.currentTimeMillis())
        val paired = json.decodeFromString(PairResponse.serializer(), client.pair(code, "replica-A").bodyAsText())

        // ops authored under the bound replica id are accepted
        val own = PushRequest(listOf(Ops(device = "replica-A").setField(id(1), "title", str("mine"), hlc(10))))
        assertEquals(HttpStatusCode.OK, client.signedPush(paired.deviceId, "n-own", own).status)

        // ops claiming any other authoring id are rejected — signature alone is not enough
        val foreign = PushRequest(listOf(Ops(device = "replica-B").setField(id(2), "title", str("forged"), hlc(11))))
        assertEquals(HttpStatusCode.Forbidden, client.signedPush(paired.deviceId, "n-foreign", foreign).status)
    }

    @Test
    fun pairingPageMintsACodeAndRendersTheQr() = testApplication {
        val h = harness()
        application {
            zyncModule(
                SyncService(h.db),
                auth = h.auth(now),
                pairing = PairingEndpoint(h.manager, h.identity, publicAddress = "https://zync.example"),
            )
        }

        val page = client.get("/settings/pairing").bodyAsText()
        assertTrue("zync://pair?h=" in page, "page must carry the pairing URI")
        assertTrue("<svg" in page, "page must render the QR as SVG")

        // The minted code in the page is redeemable exactly once.
        val code = Regex("c=([A-Z2-9]+)&").find(page)!!.groupValues[1]
        val paired = client.pair(code, "replica-A")
        assertEquals(HttpStatusCode.OK, paired.status)
        assertEquals(HttpStatusCode.Unauthorized, client.pair(code, "replica-A").status)
    }

    @Test
    fun pairingPageAbsentWithoutAPublicAddress() = testApplication {
        val h = harness()
        application {
            zyncModule(SyncService(h.db), auth = h.auth(now), pairing = PairingEndpoint(h.manager, h.identity))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/settings/pairing").status)
    }

    @Test
    fun invalidCodeIsRejectedAndDeviceStaysUnauthorized() = testApplication {
        val h = harness()
        application {
            zyncModule(SyncService(h.db), auth = h.auth(now), pairing = PairingEndpoint(h.manager, h.identity))
        }

        val pair = client.pair("WRONGCODE", "replica-A")
        assertEquals(HttpStatusCode.Unauthorized, pair.status)

        // unpaired device's signed request is still rejected
        val response = client.get("/sync/pull?since=0") {
            header("X-Device-Id", PairingManager.fingerprint(devicePub))
            header("X-Timestamp", now.toString())
            header("X-Nonce", "n2")
            header("X-Signature", sign("GET", "/sync/pull", "n2", query = "since=0"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
