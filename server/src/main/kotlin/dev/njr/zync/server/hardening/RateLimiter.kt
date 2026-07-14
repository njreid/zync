package dev.njr.zync.server.hardening

/** Per-key request admission control. */
fun interface RateLimiter {
    fun tryAcquire(key: String): Boolean
}

/**
 * Token-bucket limiter keyed by client IP. [capacity] tokens, refilled at
 * [refillPerSecond]; each request costs one. Time is injected for deterministic
 * tests. Thread-safe.
 *
 * The bucket map is bounded: buckets idle past [idleExpiryMillis] are purged
 * whenever the map exceeds [maxKeys], and if every bucket is live the
 * least-recently-used one is evicted — so a client cycling keys cannot grow
 * the map without limit. Evicting a bucket refunds its tokens (the bucket
 * restarts full), which is acceptable: the cap is a memory bound, not an
 * accounting guarantee.
 */
class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxKeys: Int = 10_000,
    private val idleExpiryMillis: Long = 60 * 60 * 1000L,
) : RateLimiter {
    private class Bucket(var tokens: Double, var last: Long)
    private val buckets = mutableMapOf<String, Bucket>()

    @Synchronized
    override fun tryAcquire(key: String): Boolean {
        val t = now()
        if (key !in buckets && buckets.size >= maxKeys) {
            buckets.values.removeAll { t - it.last > idleExpiryMillis }
            if (buckets.size >= maxKeys) {
                buckets.minByOrNull { it.value.last }?.let { buckets.remove(it.key) }
            }
        }
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
