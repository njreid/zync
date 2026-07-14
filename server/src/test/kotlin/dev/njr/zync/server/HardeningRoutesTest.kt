package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.auth.AuthOutcome
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.hardening.Hardening
import dev.njr.zync.server.hardening.MetricsSnapshot
import dev.njr.zync.server.hardening.TokenBucketRateLimiter
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardeningRoutesTest {
    private fun hardening() = Hardening(
        rateLimiter = TokenBucketRateLimiter(capacity = 2, refillPerSecond = 0.0, now = { 0L }),
        maxRequestBytes = 10,
    )

    @Test
    fun rateLimitTripsAfterCapacity() = testApplication {
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), hardening = hardening()) }
        assertEquals(HttpStatusCode.OK, client.get("/sync/pull?since=0").status)
        assertEquals(HttpStatusCode.OK, client.get("/sync/pull?since=0").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/sync/pull?since=0").status)
    }

    @Test
    fun oversizedRequestRejected() = testApplication {
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), hardening = hardening()) }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/sync/push") { setBody(ByteArray(50)) }.status)
    }

    @Test
    fun metricsSplitRejectionsCountTrafficAndAuthFailures() = testApplication {
        val h = Hardening(rateLimiter = TokenBucketRateLimiter(capacity = 2, refillPerSecond = 0.0, now = { 0L }), maxRequestBytes = 10)
        val deny = ServerAuth(
            { call -> if (call.request.headers["X-Ok"] == "1") AuthOutcome.Authorized("t") else AuthOutcome.Unauthorized("no") },
            sessions = null,
        )
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), auth = deny, hardening = h) }

        client.get("/sync/pull?since=0") { header("X-Ok", "1") } // pull counted
        client.get("/sync/pull?since=0") // 401 → authFailures
        client.post("/sync/push") { setBody(ByteArray(50)) } // 413 → oversized
        client.get("/sync/pull?since=0") { header("X-Ok", "1") } // 429 → rateLimited (bucket drained)

        val metrics = Json.decodeFromString(MetricsSnapshot.serializer(), client.get("/metrics") { header("X-Ok", "1") }.bodyAsText())
        assertEquals(1, metrics.oversized)
        assertEquals(1, metrics.rateLimited)
        assertEquals(metrics.oversized + metrics.rateLimited, metrics.rejected)
        assertTrue(metrics.authFailures >= 1, "401 on /sync counted")
        assertTrue(metrics.pulls >= 1, "authorized pull counted")
    }

    @Test
    fun healthIsExemptAndMetricsReport() = testApplication {
        val h = hardening()
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), hardening = h) }
        // health never rate-limited
        repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/health").status) }

        client.get("/sync/pull?since=0") // counted
        client.post("/sync/push") { setBody(ByteArray(50)) } // rejected (413)
        val metrics = Json.decodeFromString(MetricsSnapshot.serializer(), client.get("/metrics").bodyAsText())
        assertTrue(metrics.requests >= 2, "sync requests counted")
        assertTrue(metrics.rejected >= 1, "the oversized request was counted as rejected")
    }
}
