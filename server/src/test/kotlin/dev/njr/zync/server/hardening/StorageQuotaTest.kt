package dev.njr.zync.server.hardening

import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.Ops
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import dev.njr.zync.server.sync.CompactionPolicy
import dev.njr.zync.server.sync.OplogCompactor
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.server.zyncModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageQuotaTest {
    private val json = Json

    @Test
    fun capBlocksAndCompactionUnblocks() {
        val db = JvmZyncDatabase.inMemory()
        val svc = SyncService(db)
        var clock = 0L
        val quota = StorageQuota(db, maxOplogBytes = 500, now = { clock }, cacheMillis = 60_000)

        assertTrue(quota.allowsPush(), "empty log is under quota")

        // Grow the log past the cap; the cached verdict holds until the TTL lapses.
        val ops = Ops() // one instance: its opId counter mints unique ids across the batch
        svc.push(PushRequest((1..20).map { ops.setField(id(it), "title", str("x".repeat(40)), hlc(it.toLong())) }))
        assertTrue(quota.allowsPush(), "stale cache still answers under-quota")
        clock += 60_000
        assertFalse(quota.allowsPush(), "refreshed cache sees the cap exceeded")

        // Compaction is the relief valve: prune, lapse the TTL, pushes resume.
        OplogCompactor(db, CompactionPolicy(retainOps = 1, retainMillis = 0), now = { clock }).compactOnce()
        clock += 60_000
        assertTrue(quota.allowsPush(), "under quota again after compaction")
    }

    @Test
    fun disabledQuotaAlwaysAllows() {
        val quota = StorageQuota(JvmZyncDatabase.inMemory(), maxOplogBytes = 0)
        assertTrue(quota.allowsPush())
    }

    @Test
    fun overQuotaPushGets507AndIsCounted() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val service = SyncService(db)
        service.push(PushRequest(listOf(Ops().setField(id(1), "title", str("seed"), hlc(1)))))

        val hardening = Hardening(RateLimiter { true })
        application {
            zyncModule(
                service,
                hardening = hardening,
                quota = StorageQuota(db, maxOplogBytes = 1, cacheMillis = 0),
            )
        }

        val body = json.encodeToString(
            PushRequest.serializer(),
            PushRequest(listOf(Ops().setField(id(2), "title", str("blocked"), hlc(2)))),
        )
        val response = client.post("/sync/push") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
        assertEquals(HttpStatusCode.InsufficientStorage, response.status)
        assertEquals(1L, hardening.metrics.snapshot().quotaRejected)
    }
}
