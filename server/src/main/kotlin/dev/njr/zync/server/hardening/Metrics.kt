package dev.njr.zync.server.hardening

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * One `/metrics` read: monotonic counters since process start, plus storage gauges
 * (sampled at snapshot time via [UsageGauges]) so an external alerter can watch
 * quota headroom and compaction progress (threat model T3/T8/T9 groundwork).
 */
@Serializable
data class MetricsSnapshot(
    val requests: Long,
    val rejected: Long,
    val rateLimited: Long,
    val oversized: Long,
    val authFailures: Long,
    val pushes: Long,
    val pulls: Long,
    val bootstraps: Long,
    val opsReceived: Long,
    val blobPuts: Long,
    val blobGets: Long,
    val blobPutBytes: Long,
    val quotaRejected: Long,
    val compactionRuns: Long,
    val opsCompacted: Long,
    // Gauges (current values, not counters).
    val opLogOps: Long = 0,
    val opLogBytes: Long = 0,
    val blobTotalBytes: Long = 0,
    val compactionFloor: Long = 0,
)

/** Storage gauges sampled when a snapshot is taken (op-log size, blob usage, compaction floor). */
data class UsageGauges(
    val opLogOps: Long = 0,
    val opLogBytes: Long = 0,
    val blobTotalBytes: Long = 0,
    val compactionFloor: Long = 0,
)

/** Minimal in-process counters exposed at `/metrics`. `rejected` aggregates all refusals. */
class Metrics {
    private val requests = AtomicLong()
    private val rejected = AtomicLong()
    private val rateLimited = AtomicLong()
    private val oversized = AtomicLong()
    private val authFailures = AtomicLong()
    private val pushes = AtomicLong()
    private val pulls = AtomicLong()
    private val bootstraps = AtomicLong()
    private val opsReceived = AtomicLong()
    private val blobPuts = AtomicLong()
    private val blobGets = AtomicLong()
    private val blobPutBytes = AtomicLong()
    private val quotaRejected = AtomicLong()
    private val compactionRuns = AtomicLong()
    private val opsCompacted = AtomicLong()

    fun onRequest() { requests.incrementAndGet() }
    fun onRateLimited() { rejected.incrementAndGet(); rateLimited.incrementAndGet() }
    fun onOversized() { rejected.incrementAndGet(); oversized.incrementAndGet() }
    fun onAuthFailure() { authFailures.incrementAndGet() }
    fun onPush(ops: Int) { pushes.incrementAndGet(); opsReceived.addAndGet(ops.toLong()) }
    fun onPull() { pulls.incrementAndGet() }
    fun onBootstrap() { bootstraps.incrementAndGet() }
    fun onBlobPut(bytes: Int) { blobPuts.incrementAndGet(); blobPutBytes.addAndGet(bytes.toLong()) }
    fun onBlobGet() { blobGets.incrementAndGet() }
    fun onQuotaRejected() { rejected.incrementAndGet(); quotaRejected.incrementAndGet() }
    fun onCompaction(deleted: Long) { compactionRuns.incrementAndGet(); opsCompacted.addAndGet(deleted) }

    fun snapshot(gauges: UsageGauges = UsageGauges()) = MetricsSnapshot(
        requests = requests.get(),
        rejected = rejected.get(),
        rateLimited = rateLimited.get(),
        oversized = oversized.get(),
        authFailures = authFailures.get(),
        pushes = pushes.get(),
        pulls = pulls.get(),
        bootstraps = bootstraps.get(),
        opsReceived = opsReceived.get(),
        blobPuts = blobPuts.get(),
        blobGets = blobGets.get(),
        blobPutBytes = blobPutBytes.get(),
        quotaRejected = quotaRejected.get(),
        compactionRuns = compactionRuns.get(),
        opsCompacted = opsCompacted.get(),
        opLogOps = gauges.opLogOps,
        opLogBytes = gauges.opLogBytes,
        blobTotalBytes = gauges.blobTotalBytes,
        compactionFloor = gauges.compactionFloor,
    )
}
