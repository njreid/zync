package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.AllowedDeviceEntity
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.ChallengeDto
import dev.njr.zync.pairing.NsdAdvertiser
import dev.njr.zync.pairing.PairingService
import dev.njr.zync.pairing.QrPayload
import dev.njr.zync.pairing.RemoteAccessManager
import dev.njr.zync.pairing.ServerBinding
import dev.njr.zync.pairing.ServerCertStore
import dev.njr.zync.pairing.ServerController
import dev.njr.zync.pairing.SessionRequestBody
import dev.njr.zync.pairing.PasswordProtector
import dev.njr.zync.pairing.WifiIpAddressProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Fake [ServerController] — just records the last `LanConfig` it was asked to (re)start with. */
private class FakeServerController : ServerController {
    override fun restart(lan: LanConfig?): ServerBinding =
        ServerBinding(httpPort = 8080, tlsPort = if (lan != null) 8443 else null)
}

private class FakeNsdAdvertiser : NsdAdvertiser {
    var unregisterCalls = 0
    override fun register(port: Int, name: String, fingerprintHint: String) = Unit
    override fun unregister() { unregisterCalls++ }
}

private class FakeWifiIpAddressProvider : WifiIpAddressProvider {
    override fun currentIpv4(): String = "192.168.1.50"
}

private class FakePasswordProtector : PasswordProtector {
    override fun protect(plain: CharArray): ByteArray = String(plain).toByteArray(Charsets.UTF_8)
    override fun unprotect(protected: ByteArray): CharArray = String(protected, Charsets.UTF_8).toCharArray()
}

/**
 * Covers the settings-facing device-management routes: `/devices`, `/devices/{id}/revoke`,
 * the remote-access toggle routes, and `/pair/approve` — all of which must be reachable only from the phone's own
 * loopback WebView, never from the LAN connector (see `requireLoopbackConnector` in
 * `PairingRoutes.kt`). Uses the same `testApplication` + `url { protocol = URLProtocol.HTTPS }`
 * trick as `PairingRoutesTest` to simulate a request arriving on the LAN connector without a real
 * TLS handshake.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceMgmtRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val loopbackToken = "loopback-token"

    private fun qrPayload(pubkey: String, nonce: String, name: String = "Laptop") =
        json.encodeToString(QrPayload.serializer(), QrPayload(pubkey, name, nonce))

    private fun sign(key: Ed25519PrivateKeyParameters, message: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    private fun runWithApp(
        block: suspend ApplicationTestBuilder.(PairingService, HttpClient) -> Unit,
    ) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = ZyncDatabase.inMemory(ctx)
        val repo = NodeRepository(db)
        val pairing = PairingService(db.allowedDeviceDao(), randomNonce = { UUID.randomUUID().toString() })
        pairing.remoteAccess = RemoteAccessManager(
            certStore = ServerCertStore(ctx.filesDir, FakePasswordProtector()),
            server = FakeServerController(),
            pairingService = pairing,
            nsd = FakeNsdAdvertiser(),
            wifiIp = FakeWifiIpAddressProvider(),
            deviceName = "Test Phone",
        )
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

    // ---- /devices --------------------------------------------------------------------------

    @Test
    fun `GET devices lists inserted devices with lastSeen`() = runWithApp { pairing, client ->
        val pending = pairing.beginPairing()
        val approved = pairing.approveScanned(qrPayload("pubkey-a", pending.nonce))

        val res = client.get("/devices") { header(TOKEN_HEADER, loopbackToken) }
        assertEquals(HttpStatusCode.OK, res.status)
        val devices: List<AllowedDeviceDto> = res.body()
        assertEquals(1, devices.size)
        assertEquals("Laptop", devices[0].name)
        assertEquals(approved.id, devices[0].id)
        assertFalse(devices[0].revoked)
    }

    @Test
    fun `revoking a device flips revoked and a subsequent session issuance fails`() =
        runWithApp { pairing, client ->
            val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
            val pubkeyB64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
            val pending = pairing.beginPairing()
            val approved = pairing.approveScanned(qrPayload(pubkeyB64, pending.nonce))
            pairing.completePairingRequest(pubkeyB64, pending.nonce)

            val revokeRes = client.post("/devices/${approved.id}/revoke") { header(TOKEN_HEADER, loopbackToken) }
            assertEquals(HttpStatusCode.OK, revokeRes.status)

            val devicesRes = client.get("/devices") { header(TOKEN_HEADER, loopbackToken) }
            val devices: List<AllowedDeviceDto> = devicesRes.body()
            assertTrue(devices.single { it.id == approved.id }.revoked)

            // A subsequent session issuance for the now-revoked device must fail.
            val challengeRes = client.get("/pair/challenge") { parameter("devicePubkey", pubkeyB64) }
            val challenge: ChallengeDto = challengeRes.body()
            val signature = sign(privateKey, challenge.challenge.toByteArray(Charsets.UTF_8))
            val sessionRes = client.post("/pair/session") {
                contentType(ContentType.Application.Json)
                setBody(SessionRequestBody(pubkeyB64, challenge.challenge, signature))
            }
            assertEquals(HttpStatusCode.Unauthorized, sessionRes.status)
        }

    @Test
    fun `devices route is 403 over the LAN connector even with a valid session`() =
        runWithApp { pairing, client ->
            val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
            val pubkeyB64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
            val pending = pairing.beginPairing()
            pairing.approveScanned(qrPayload(pubkeyB64, pending.nonce))
            pairing.completePairingRequest(pubkeyB64, pending.nonce)
            val challenge = pairing.newChallenge()
            val token = pairing.issueSession(
                pubkeyB64, challenge, sign(privateKey, challenge.toByteArray(Charsets.UTF_8)),
            )!!

            val res = client.get("/devices") {
                url { protocol = URLProtocol.HTTPS }
                header("Authorization", "Bearer $token")
            }
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    // ---- /remote/* ---------------------------------------------------------------------------

    @Test
    fun `remote enable, state, and disable work over loopback`() = runWithApp { _, client ->
        val enableRes = client.post("/remote/enable") { header(TOKEN_HEADER, loopbackToken) }
        assertEquals(HttpStatusCode.OK, enableRes.status)
        val info: RemoteInfoDto = enableRes.body()
        assertEquals("192.168.1.50", info.ip)
        assertEquals(8443, info.tlsPort)
        assertTrue(info.certFingerprint.isNotBlank())

        val stateRes = client.get("/remote/state") { header(TOKEN_HEADER, loopbackToken) }
        val state: RemoteStateDto = stateRes.body()
        assertTrue(state.enabled)
        assertEquals("192.168.1.50", state.ip)

        val disableRes = client.post("/remote/disable") { header(TOKEN_HEADER, loopbackToken) }
        assertEquals(HttpStatusCode.OK, disableRes.status)
        val stateAfter: RemoteStateDto = client.get("/remote/state") { header(TOKEN_HEADER, loopbackToken) }.body()
        assertFalse(stateAfter.enabled)
    }

    @Test
    fun `remote enable is 403 over the LAN connector`() = runWithApp { _, client ->
        val res = client.post("/remote/enable") { url { protocol = URLProtocol.HTTPS } }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `remote state is 403 over the LAN connector`() = runWithApp { _, client ->
        val res = client.get("/remote/state") { url { protocol = URLProtocol.HTTPS } }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    // ---- /pair/approve -----------------------------------------------------------------------

    @Test
    fun `pair approve over loopback returns the confirm code`() = runWithApp { pairing, client ->
        val pending = pairing.beginPairing()
        val res = client.post("/pair/approve") {
            header(TOKEN_HEADER, loopbackToken)
            contentType(ContentType.Application.Json)
            setBody(PairApproveBody(qrPayload("some-pubkey", pending.nonce)))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val dto: ConfirmCodeDto = res.body()
        assertEquals(pending.confirmCode, dto.confirmCode)
    }

    @Test
    fun `pair approve is 403 over the LAN connector regardless of token`() = runWithApp { pairing, client ->
        val pending = pairing.beginPairing()
        val res = client.post("/pair/approve") {
            url { protocol = URLProtocol.HTTPS }
            contentType(ContentType.Application.Json)
            setBody(PairApproveBody(qrPayload("some-pubkey", pending.nonce)))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }
}
