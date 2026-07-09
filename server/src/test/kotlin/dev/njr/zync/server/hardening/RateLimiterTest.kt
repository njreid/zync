package dev.njr.zync.server.hardening

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun tripsAfterCapacityThenRefills() {
        var now = 0L
        val limiter = TokenBucketRateLimiter(capacity = 2, refillPerSecond = 1.0, now = { now })
        assertTrue(limiter.tryAcquire("dev"))
        assertTrue(limiter.tryAcquire("dev"))
        assertFalse(limiter.tryAcquire("dev"), "bucket exhausted")

        now = 1_000 // one second → one token refilled
        assertTrue(limiter.tryAcquire("dev"))
        assertFalse(limiter.tryAcquire("dev"))
    }

    @Test
    fun keysAreIndependent() {
        val limiter = TokenBucketRateLimiter(capacity = 1, refillPerSecond = 0.0, now = { 0L })
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"), "a different principal has its own bucket")
    }
}
