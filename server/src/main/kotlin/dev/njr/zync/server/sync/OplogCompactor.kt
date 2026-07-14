package dev.njr.zync.server.sync

import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.hardening.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Retention policy for [OplogCompactor]. Deletion requires *all* guards to pass,
 * so the effective floor is the most conservative candidate. Defaults (spec left
 * these open — M9 choice, env-overridable in `Main`): keep the newest 10k ops and
 * everything authored in the last 30 days.
 */
data class CompactionPolicy(
    /** Always keep at least this many newest ops (bootstrap tail + audit window). */
    val retainOps: Long = 10_000,
    /** Never delete an op authored (wall clock) within this window. */
    val retainMillis: Long = 30L * 24 * 60 * 60 * 1000,
) {
    companion object {
        /** Reads `ZYNC_OPLOG_RETAIN_OPS` / `ZYNC_OPLOG_RETAIN_DAYS` (defaults 10000 / 30). */
        fun fromEnv(env: (String) -> String?) = CompactionPolicy(
            retainOps = env("ZYNC_OPLOG_RETAIN_OPS")?.toLong() ?: 10_000,
            retainMillis = (env("ZYNC_OPLOG_RETAIN_DAYS")?.toLong() ?: 30L) * 24 * 60 * 60 * 1000,
        )
    }
}

/** One compaction pass: the recorded floor afterwards + how many op_log rows were deleted. */
data class CompactionResult(val floor: Long, val deleted: Long)

/**
 * Op-log compaction (resolves the op/merge spec §11 open question). The merged CRDT
 * state lives in the materialized register/tombstone/tag/move tables — op_log rows
 * are transport ("what's new" by `seq`) + audit history, made redundant below the
 * floor by bootstrap (snapshot + tail). A pass deletes ops with `seq <= floor` where
 * floor is the minimum of:
 *  - count retention: `head - retainOps`,
 *  - age retention: just below the first op authored inside `retainMillis`,
 *  - peer cursors: the lowest cursor recorded in `sync_state` (if any).
 *
 * The floor is persisted in `sync_state` under [FLOOR_PEER] and only ever grows;
 * pulls with `since < floor` get **410 Gone** (see `syncRoutes`) and must
 * re-bootstrap. Merge state is untouched by construction — `OplogCompactorTest`
 * proves the projected state is identical before/after and that a fresh replica
 * (bootstrap + tail) still converges to it. Note: `applied_op` is deliberately not
 * pruned, so re-delivery of a compacted op stays a merge no-op.
 */
class OplogCompactor(
    private val db: ZyncDatabase,
    private val policy: CompactionPolicy = CompactionPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
    private val metrics: Metrics? = null,
) {
    private val log = LoggerFactory.getLogger("zync.compaction")

    /** The current compaction floor (0 = nothing compacted; pulls below it are 410). */
    fun floor(): Long = db.transportQueries.getCursor(FLOOR_PEER).executeAsOneOrNull() ?: 0L

    /** Run one compaction pass; a no-op when every guard already covers the log. */
    fun compactOnce(): CompactionResult {
        var floor = 0L
        var deleted = 0L
        db.transaction {
            val q = db.transportQueries
            val head = q.headSeq().executeAsOne()
            val current = q.getCursor(FLOOR_PEER).executeAsOneOrNull() ?: 0L

            val byCount = head - policy.retainOps
            val firstRecent = q.firstSeqAtOrAfterWallClock(now() - policy.retainMillis).executeAsOne()
            val byAge = if (firstRecent == -1L) head else firstRecent - 1
            val minCursor = q.minPeerCursor(FLOOR_PEER).executeAsOne()
            val byCursor = if (minCursor == -1L) Long.MAX_VALUE else minCursor

            floor = minOf(byCount, byAge, byCursor).coerceAtLeast(current)
            if (floor > current) {
                val before = q.countOps().executeAsOne()
                q.deleteOpsThroughSeq(floor)
                deleted = before - q.countOps().executeAsOne()
                q.setCursor(FLOOR_PEER, floor)
            }
        }
        metrics?.onCompaction(deleted)
        if (deleted > 0) log.info("compacted deleted={} floor={}", deleted, floor)
        return CompactionResult(floor, deleted)
    }

    /** Run [compactOnce] every [interval] (first pass immediately); failures are logged, not fatal. */
    fun start(scope: CoroutineScope, interval: Duration): Job = scope.launch {
        while (isActive) {
            runCatching { compactOnce() }.onFailure { log.warn("compaction pass failed", it) }
            delay(interval)
        }
    }

    companion object {
        /** Reserved `sync_state` row recording the compaction floor (not a real peer). */
        const val FLOOR_PEER = "@compaction_floor"
    }
}
