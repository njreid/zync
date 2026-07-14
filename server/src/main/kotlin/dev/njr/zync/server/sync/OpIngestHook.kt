package dev.njr.zync.server.sync

import dev.njr.zync.core.op.Op

/**
 * The single hook the ingest path calls after ops land durably (op_log row +
 * merged state, transaction committed). [ops] are the newly ingested ops with
 * their transport `seq` assigned — dedup-skipped ops are never reported. This
 * is the M8 operator runtime's trigger feed; implementations must not throw
 * and should hand work off (the callback runs on the ingesting thread).
 */
fun interface OpIngestHook {
    fun onIngested(ops: List<Op>)
}

/**
 * A late-bindable [OpIngestHook], for the wiring cycle "SyncService needs the
 * hook; the operator runtime needs SyncService to emit". Construct the service
 * with this, then point [delegate] at the runtime.
 */
class SettableIngestHook : OpIngestHook {
    @Volatile
    var delegate: OpIngestHook = OpIngestHook {}

    override fun onIngested(ops: List<Op>) = delegate.onIngested(ops)
}
