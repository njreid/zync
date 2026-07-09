package dev.njr.zync.core.clock

import kotlinx.serialization.Serializable

/**
 * A Hybrid Logical Clock timestamp — the **conflict-resolution order** for the
 * merge (distinct from the server's transport `seq`). Total order is
 * `physical → counter → deviceId`; higher wins under LWW.
 *
 * Immutable value. Generation/advancement lives in [HlcGenerator] because it is
 * inherently stateful (it tracks the last-issued clock).
 */
@Serializable
data class Hlc(
    val physical: Long,
    val counter: Int,
    val deviceId: String,
) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        val p = physical.compareTo(other.physical)
        if (p != 0) return p
        val c = counter.compareTo(other.counter)
        if (c != 0) return c
        return deviceId.compareTo(other.deviceId)
    }

    /** Compact single-string encoding for storage (deviceId may contain colons). */
    fun pack(): String = "$physical:$counter:$deviceId"

    companion object {
        /** Zero clock for a device — the starting point before any op is issued. */
        fun zero(deviceId: String): Hlc = Hlc(0L, 0, deviceId)

        fun unpack(packed: String): Hlc {
            val parts = packed.split(":", limit = 3)
            require(parts.size == 3) { "Malformed packed HLC: '$packed'" }
            return Hlc(parts[0].toLong(), parts[1].toInt(), parts[2])
        }
    }
}

/**
 * Stateful HLC generator for a single device. [now] issues a monotonically
 * increasing local timestamp; [observe] merges a remote clock on receipt of a
 * remote op so this device's future timestamps sort after everything it has seen.
 *
 * Not thread-safe: callers serialize op issuance (a single writer per replica).
 */
class HlcGenerator(
    private val deviceId: String,
    private val clock: Clock,
    initial: Hlc = Hlc.zero(deviceId),
) {
    private var last: Hlc = initial

    /** The last-issued clock (no advancement). */
    fun current(): Hlc = last

    /** Issue the next local timestamp. */
    fun now(): Hlc {
        val wall = clock.nowMillis()
        val p = maxOf(wall, last.physical)
        val c = if (p == last.physical) last.counter + 1 else 0
        return Hlc(p, c, deviceId).also { last = it }
    }

    /** Advance past a remote clock; call on receiving any remote op. */
    fun observe(remote: Hlc): Hlc {
        val wall = clock.nowMillis()
        val p = maxOf(wall, last.physical, remote.physical)
        val c = when {
            p == last.physical && p == remote.physical -> maxOf(last.counter, remote.counter) + 1
            p == last.physical -> last.counter + 1
            p == remote.physical -> remote.counter + 1
            else -> 0
        }
        return Hlc(p, c, deviceId).also { last = it }
    }
}
