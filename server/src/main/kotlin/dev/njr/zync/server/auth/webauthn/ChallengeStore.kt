package dev.njr.zync.server.auth.webauthn

/**
 * Short-lived, consume-once store of the WebAuthn challenges the server has issued. A
 * ceremony is only accepted if it echoes a challenge we issued and haven't seen before
 * (anti-replay). Keyed by the base64url challenge string as it appears in clientDataJSON.
 */
class ChallengeStore(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableMapOf<String, Long>() // challenge -> expiry

    @Synchronized
    fun issue(challenge: String) {
        prune()
        pending[challenge] = now() + ttlMillis
    }

    /** True if [challenge] was issued and unexpired; consumes it so it can't be replayed. */
    @Synchronized
    fun consume(challenge: String): Boolean {
        prune()
        val expiry = pending.remove(challenge) ?: return false
        return expiry > now()
    }

    private fun prune() {
        val t = now()
        pending.entries.removeAll { it.value <= t }
    }
}
