package dev.njr.zync.server.auth

/**
 * Remembers recently-seen request nonces to reject replays. Entries live as long as
 * the signed-request timestamp window (a nonce older than the window can't be
 * replayed anyway, since the timestamp check would reject it first).
 */
class NonceCache(private val ttlMillis: Long) {
    private val seen = mutableMapOf<String, Long>() // nonce -> expiry

    /** Record [nonce]; return true if it was new (not a replay). */
    @Synchronized
    fun checkAndRecord(nonce: String, now: Long): Boolean {
        purge(now)
        if (seen.containsKey(nonce)) return false
        seen[nonce] = now + ttlMillis
        return true
    }

    private fun purge(now: Long) {
        val expired = seen.filterValues { it <= now }.keys
        expired.forEach { seen.remove(it) }
    }
}
