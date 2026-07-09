package dev.njr.zync.server.hardening

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class MetricsSnapshot(val requests: Long, val rejected: Long)

/** Minimal in-process counters exposed at `/metrics`. */
class Metrics {
    private val requests = AtomicLong()
    private val rejected = AtomicLong()

    fun onRequest() { requests.incrementAndGet() }
    fun onRejected() { rejected.incrementAndGet() }

    fun snapshot() = MetricsSnapshot(requests.get(), rejected.get())
}
