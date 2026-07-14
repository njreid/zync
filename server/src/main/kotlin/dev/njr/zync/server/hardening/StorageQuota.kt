package dev.njr.zync.server.hardening

import dev.njr.zync.data.db.ZyncDatabase

/**
 * Op-log storage quota: refuses further `/sync/push` ingestion once the op-log
 * payload bytes exceed [maxOplogBytes] (0 disables). Protects the box's SQLite
 * volume — the one local resource a runaway writer can exhaust; blob bytes live in
 * S3 and are capped by bucket lifecycle/budget instead (see deploy/bootstrap.md).
 *
 * The size query is a full-table SUM, so the answer is cached for [cacheMillis]
 * — quota is a disk guard, not an accounting invariant, and being up to a minute
 * stale is fine. Compaction (OplogCompactor) is the pressure-relief valve: once it
 * frees space, pushes resume on the next refresh.
 */
class StorageQuota(
    private val db: ZyncDatabase,
    private val maxOplogBytes: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 60_000,
) {
    @Volatile private var cachedBytes = -1L
    @Volatile private var cachedAt = Long.MIN_VALUE

    /** True when ingestion is allowed (quota disabled or under the cap). */
    fun allowsPush(): Boolean {
        if (maxOplogBytes <= 0) return true
        val t = now()
        if (cachedBytes < 0 || t - cachedAt >= cacheMillis) {
            cachedBytes = db.transportQueries.oplogBytes().executeAsOne()
            cachedAt = t
        }
        return cachedBytes < maxOplogBytes
    }

    companion object {
        /** Reads `ZYNC_QUOTA_OPLOG_MB` (default 1024 MiB; 0 disables). */
        fun fromEnv(db: ZyncDatabase, env: (String) -> String?) =
            StorageQuota(db, maxOplogBytes = (env("ZYNC_QUOTA_OPLOG_MB")?.toLong() ?: 1024L) * 1024 * 1024)
    }
}
