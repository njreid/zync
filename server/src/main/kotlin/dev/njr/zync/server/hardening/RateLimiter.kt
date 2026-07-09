package dev.njr.zync.server.hardening

/** Per-key request admission control. */
fun interface RateLimiter {
    fun tryAcquire(key: String): Boolean
}

/**
 * Token-bucket limiter keyed by principal (device id or client IP). [capacity]
 * tokens, refilled at [refillPerSecond]; each request costs one. Time is injected
 * for deterministic tests. Thread-safe.
 */
class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val now: () -> Long = System::currentTimeMillis,
) : RateLimiter {
    private class Bucket(var tokens: Double, var last: Long)
    private val buckets = mutableMapOf<String, Bucket>()

    @Synchronized
    override fun tryAcquire(key: String): Boolean {
        val t = now()
        val bucket = buckets.getOrPut(key) { Bucket(capacity.toDouble(), t) }
        val elapsedSeconds = (t - bucket.last).coerceAtLeast(0) / 1000.0
        bucket.tokens = (bucket.tokens + elapsedSeconds * refillPerSecond).coerceAtMost(capacity.toDouble())
        bucket.last = t
        return if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            true
        } else {
            false
        }
    }
}
