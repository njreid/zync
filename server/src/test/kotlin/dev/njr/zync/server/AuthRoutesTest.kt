package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.auth.Ed25519
import dev.njr.zync.server.auth.InMemoryDeviceRegistry
import dev.njr.zync.server.auth.LoginRequest
import dev.njr.zync.server.auth.LoginResponse
import dev.njr.zync.server.auth.NonceCache
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.auth.SignedRequestVerifier
import dev.njr.zync.server.auth.ZyncAuthenticator
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

@OptIn(ExperimentalEncodingApi::class)
class AuthRoutesTest {
    private val json = Json
    private val seed = ByteArray(32) { (it + 3).toByte() }
    private val now = 5_000_000L

    private fun serverAuth(): ServerAuth {
        val registry = InMemoryDeviceRegistry().apply { register("phone", Ed25519.publicKeyFor(seed)) }
        val verifier = SignedRequestVerifier(registry, NonceCache(300_000), windowMillis = 300_000)
        val sessions = SessionStore(credentialCheck = { it == "hunter2" })
        return ServerAuth(ZyncAuthenticator(verifier, sessions, now = { now }), sessions)
    }

    @Test
    fun unauthenticatedSyncIsRejected() = testApplication {
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), serverAuth()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/pull?since=0").status)
    }

    @Test
    fun deviceSignedSyncIsAccepted() = testApplication {
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), serverAuth()) }
        val sig = Base64.encode(Ed25519.sign(seed, SignedRequestVerifier.canonicalString("GET", "/sync/pull", now, "nonce-1").encodeToByteArray()))
        val response = client.get("/sync/pull?since=0") {
            header("X-Device-Id", "phone")
            header("X-Timestamp", now.toString())
            header("X-Nonce", "nonce-1")
            header("X-Signature", sig)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun browserLoginThenSessionBearerIsAccepted() = testApplication {
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), serverAuth()) }

        val loginBody = client.post("/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest("hunter2")))
        }.bodyAsText()
        val token = json.decodeFromString(LoginResponse.serializer(), loginBody).token

        val ok = client.get("/sync/pull?since=0") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)

        val bad = client.post("/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest("wrong")))
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
    }
}
