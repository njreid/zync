package dev.njr.zync.server

import dev.njr.zync.core.integrations.NewzHandoffResponse
import dev.njr.zync.core.integrations.NewzRedeemRequest
import dev.njr.zync.core.integrations.NewzRedeemResponse
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.AuthOutcome
import dev.njr.zync.server.auth.Authenticator
import dev.njr.zync.server.auth.Ed25519
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.integrations.NewzIntegration
import dev.njr.zync.server.pairing.ServerIdentity
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.HttpClient
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

/**
 * The zync→newz handoff: device-gated mint, compact-JWT shape, one-time atomic
 * redemption, revoked-device + expiry + replay rejection, per-device mint rate cap.
 */
@OptIn(ExperimentalEncodingApi::class)
class NewzRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceAuth = Authenticator { AuthOutcome.Authorized("dev-1", deviceId = "dev-1") }

    private fun db(): ZyncDatabase = JvmZyncDatabase.inMemory().also {
        it.deviceQueries.upsertDevice("dev-1", "pk", 0, "replica-1")
    }

    private fun integration(db: ZyncDatabase, now: () -> Long = System::currentTimeMillis) =
        NewzIntegration(db, ServerIdentity.fromSeed(ByteArray(32) { 7 }), "https://z.example", "svc-secret", now)

    private suspend fun HttpClient.mint(): HttpResponse = post("/integrations/newz/handoff")

    private suspend fun HttpClient.redeem(jti: String, bearer: String? = "svc-secret"): HttpResponse =
        post("/integrations/newz/redeem") {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(NewzRedeemRequest.serializer(), NewzRedeemRequest(jti)))
        }

    private fun jtiOf(handoff: NewzHandoffResponse): String {
        val token = handoff.handoffUrl.substringAfter("token=")
        val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(token.split(".")[1]).decodeToString()
        return Regex("\"jti\":\"([0-9a-f]+)\"").find(payload)!!.groupValues[1]
    }

    @Test
    fun mintRedeemRoundtripAndReplayRejection() = testApplication {
        val db = db()
        val integration = integration(db)
        application { zyncModule(SyncService(db), auth = ServerAuth(deviceAuth, sessions = null), newz = integration) }

        val minted = json.decodeFromString(NewzHandoffResponse.serializer(), client.mint().bodyAsText())
        assertTrue(minted.handoffUrl.startsWith("https://z.example/newz/handoff?token="))

        // token verifies against the published key: EdDSA over "header.payload"
        val token = minted.handoffUrl.substringAfter("token=")
        val (h, p, sig) = token.split(".")
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        assertTrue(Ed25519.verify(ServerIdentity.fromSeed(ByteArray(32) { 7 }).publicKey, "$h.$p".encodeToByteArray(), b64.decode(sig)))
        val payload = b64.decode(p).decodeToString()
        assertTrue("\"iss\":\"zync\"" in payload && "\"aud\":\"newz\"" in payload && "\"sub\":\"dev-1\"" in payload)
        assertTrue("\"return_path\":\"/newz/\"" in payload)

        val jti = jtiOf(minted)
        val first = client.redeem(jti)
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("dev-1", json.decodeFromString(NewzRedeemResponse.serializer(), first.bodyAsText()).deviceId)
        // replay: second redemption of the same jti fails generically
        assertEquals(HttpStatusCode.Unauthorized, client.redeem(jti).status)
    }

    @Test
    fun redeemDemandsTheServiceCredential() = testApplication {
        val db = db()
        application { zyncModule(SyncService(db), auth = ServerAuth(deviceAuth, sessions = null), newz = integration(db)) }
        assertEquals(HttpStatusCode.Unauthorized, client.redeem("whatever", bearer = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.redeem("whatever", bearer = "wrong").status)
    }

    @Test
    fun browserSessionsCannotMint() = testApplication {
        val db = db()
        val browserOnly = Authenticator { AuthOutcome.Authorized("browser", deviceId = null) }
        application { zyncModule(SyncService(db), auth = ServerAuth(browserOnly, sessions = null), newz = integration(db)) }
        assertEquals(HttpStatusCode.Forbidden, client.mint().status)
    }

    @Test
    fun expiredTokensCannotBeRedeemed() = testApplication {
        val db = db()
        var now = 1_000_000L
        val integration = integration(db) { now }
        application { zyncModule(SyncService(db), auth = ServerAuth(deviceAuth, sessions = null), newz = integration) }
        val jti = jtiOf(json.decodeFromString(NewzHandoffResponse.serializer(), client.mint().bodyAsText()))
        now += 61_000 // past the 60s ttl
        assertEquals(HttpStatusCode.Unauthorized, client.redeem(jti).status)
    }

    @Test
    fun revokedDeviceCannotRedeemOutstandingTokens() = testApplication {
        val db = db()
        application { zyncModule(SyncService(db), auth = ServerAuth(deviceAuth, sessions = null), newz = integration(db)) }
        val jti = jtiOf(json.decodeFromString(NewzHandoffResponse.serializer(), client.mint().bodyAsText()))
        db.deviceQueries.revokeDevice("dev-1")
        assertEquals(HttpStatusCode.Unauthorized, client.redeem(jti).status)
    }

    @Test
    fun mintingIsRateLimitedPerDevice() = testApplication {
        val db = db()
        application { zyncModule(SyncService(db), auth = ServerAuth(deviceAuth, sessions = null), newz = integration(db)) }
        repeat(10) { assertEquals(HttpStatusCode.OK, client.mint().status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.mint().status)
    }
}
