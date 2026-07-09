package dev.njr.zync.server.sync

/**
 * Assigns the monotonic, gap-free transport `seq` to ops on ingest. Distinct from
 * HLC (merge order): `seq` answers "what's new since cursor X". Initialized from the
 * persisted op_log head on startup so it survives restarts; advanced inside the same
 * DB transaction that persists the op (server, Task 3).
 *
 * Thread-safe: ingest may be concurrent, but `seq` must be a strict sequence.
 */
class SeqAllocator(initialHead: Long = 0L) {
    private var head = initialHead

    @Synchronized
    fun next(): Long = ++head

    @Synchronized
    fun head(): Long = head
}
