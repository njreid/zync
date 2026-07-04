package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.ChallengeDto
import dev.njr.zync.pairing.PairRequestBody
import dev.njr.zync.pairing.PairResultDto
import dev.njr.zync.pairing.PairingService
import dev.njr.zync.pairing.QrPayload
import dev.njr.zync.pairing.SessionDto
import dev.njr.zync.pairing.SessionRequestBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the new pairing routes (mounted under `/pair`) and the guard refactor (loopback token
 * vs. LAN session token,
 * `?token=` scoping, CSP header, revoke-immediacy) end to end through a real Ktor
 * `testApplication`, driving the pairing handshake with a real in-test Ed25519 keypair.
 *
 * LAN vs. loopback: the two real connectors ([ZyncServer.start]) differ only in scheme — the
 * loopback connector is plain HTTP, the LAN connector is `sslConnector` (HTTPS) — and
 * `AuthGuard.classify` keys off exactly that (`call.request.local.scheme`). Verified empirically
 * that Ktor's `testApplication` propagates the client request's `URLProtocol` through to
 * `call.request.local.scheme` (a scratch probe test showed `client.get(...) { url { protocol =
 * URLProtocol.HTTPS } }` yields `local.scheme == "https"` server-side), so LAN-connector behavior
 * is exercised here the same way, without needing a real TLS handshake.
 */
@RunWith(RobolectricTestRunner::class)
class PairingRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val loopbackToken = "loopback-token"

    private fun sign(key: Ed25519PrivateKeyParameters, message: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    private fun qrPayload(pubkey: String, nonce: String, name: String = "Laptop") =
        json.encodeToString(QrPayload.serializer(), QrPayload(pubkey, name, nonce))

    private fun runWithApp(
        block: suspend ApplicationTestBuilder.(PairingService, HttpClient) -> Unit,
    ) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = ZyncDatabase.inMemory(ctx)
        val repo = NodeRepository(db)
        val pairing = PairingService(db.allowedDeviceDao(), randomNonce = { UUID.randomUUID().toString() })
        try {
            testApplication {
                application {
                    zyncModule(
                        db, repo, token = loopbackToken,
                        assets = { path ->
                            if (path == "index.html") "<html>ok</html>".toByteArray() to ContentType.Text.Html
                            else null
                        },
                        pairing = pairing,
                    )
                }
                val client = createClient {
                    install(ContentNegotiation) { json() }
                    install(HttpCookies)
                }
                block(pairing, client)
            }
        } finally {
            db.close()
        }
    }

    // ---- full pairing handshake through the routes ----------------------------------------

    @Test
    fun `pair request is pending until approved, then returns fingerprint and confirm code`() =
        runWithApp { pairing, client ->
            val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
            val pubkeyB64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
            pairing.setCertFingerprint("AB:CD:EF")
            val nonce = UUID.randomUUID().toString() // desktop-originated nonce (spec §8b)

            val pendingRes = client.post("/pair/request") {
                contentType(ContentType.Application.Json)
                setBody(PairRequestBody(pubkeyB64, nonce))
            }
            assertEquals(HttpStatusCode.Accepted, pendingRes.status)

            val approved = pairing.approveScanned(qrPayload(pubkeyB64, nonce)) // phone scans desktop's QR

            val approvedRes = client.post("/pair/request") {
                contentType(ContentType.Application.Json)
                setBody(PairRequestBody(pubkeyB64, nonce))
            }
            assertEquals(HttpStatusCode.OK, approvedRes.status)
            val result: PairResultDto = approvedRes.body()
            assertEquals("AB:CD:EF", result.certFingerprint)
            assertEquals(approved.confirmCode, result.confirmCode)
        }

    // ---- session bootstrap + using the session token -----------------------------------------

    @Test
    fun `challenge then session then api call with the session token succeeds`() =
        runWithApp { pairing, client ->
            val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
            val pubkeyB64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
            val nonce = UUID.randomUUID().toString()
            pairing.approveScanned(qrPayload(pubkeyB64, nonce))
            pairing.completePairingRequest(pubkeyB64, nonce)

            val challengeRes = client.get("/pair/challenge") { parameter("devicePubkey", pubkeyB64) }
            assertEquals(HttpStatusCode.OK, challengeRes.status)
            val challenge: ChallengeDto = challengeRes.body()

            val signature = sign(privateKey, challenge.challenge.toByteArray(Charsets.UTF_8))
            val sessionRes = client.post("/pair/session") {
                contentType(ContentType.Application.Json)
                setBody(SessionRequestBody(pubkeyB64, challenge.challenge, signature))
            }
            assertEquals(HttpStatusCode.OK, sessionRes.status)
            val session: SessionDto = sessionRes.body()
            assertTrue(session.token.isNotBlank())

            val apiRes = client.get("/api/roots") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }
            assertEquals(HttpStatusCode.OK, apiRes.status)
        }

    // ---- LAN connector without a session ------------------------------------------------------

    @Test
    fun `LAN request with neither credential is 403 with an empty body`() = runWithApp { _, client ->
        val res = client.get("/api/roots") { url { protocol = URLProtocol.HTTPS } }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("", res.bodyAsText())
    }

    @Test
    fun `LAN request with the loopback token instead of a session is still 403`() = runWithApp { _, client ->
        val res = client.get("/api/roots") {
            url { protocol = URLProtocol.HTTPS }
            header(TOKEN_HEADER, loopbackToken)
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    // ---- revoke immediacy, at the HTTP layer --------------------------------------------------

    @Test
    fun `revoking the device invalidates an already-issued session on the very next call`() =
        runWithApp { pairing, client ->
            val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
            val pubkeyB64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
            val nonce = UUID.randomUUID().toString()
            val approved = pairing.approveScanned(qrPayload(pubkeyB64, nonce))
            pairing.completePairingRequest(pubkeyB64, nonce)
            val challenge = pairing.newChallenge()
            val token = pairing.issueSession(pubkeyB64, challenge, sign(privateKey, challenge.toByteArray(Charsets.UTF_8)))!!

            suspend fun callApiOverLan() = client.get("/api/roots") {
                url { protocol = URLProtocol.HTTPS }
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, callApiOverLan().status)

            pairing.revoke(approved.id)

            assertEquals(
                "revocation must be effective immediately, not merely bounded by the session TTL",
                HttpStatusCode.Forbidden,
                callApiOverLan().status,
            )
        }

    // ---- ?token= scoping to the document route only ------------------------------------------

    @Test
    fun `query token on an api route is ignored and sets no cookie`() = runWithApp { _, client ->
        val res = client.get("/api/roots") { parameter("token", loopbackToken) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals(null, res.headers[HttpHeaders.SetCookie])

        // The (non-)cookie from the rejected api call must not leak into a later, otherwise
        // unauthenticated document request.
        val followUp = client.get("/index.html")
        assertEquals(HttpStatusCode.Unauthorized, followUp.status)
    }

    @Test
    fun `query token on the document route still authorizes and issues a cookie for loopback`() =
        runWithApp { _, client ->
            val first = client.get("/index.html") { parameter("token", loopbackToken) }
            assertEquals(HttpStatusCode.OK, first.status)

            val second = client.get("/index.html") // cookie now carries auth, no query param needed
            assertEquals(HttpStatusCode.OK, second.status)
        }

    // ---- CSP header -----------------------------------------------------------------------

    @Test
    fun `CSP header is present on document and api responses`() = runWithApp { _, client ->
        val doc = client.get("/index.html") { parameter("token", loopbackToken) }
        assertEquals(CSP_HEADER_VALUE, doc.headers["Content-Security-Policy"])

        val api = client.get("/api/roots") { header(TOKEN_HEADER, loopbackToken) }
        assertEquals(CSP_HEADER_VALUE, api.headers["Content-Security-Policy"])
    }
}
