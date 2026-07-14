package dev.njr.zync.server.auth

/**
 * Bundles the request [authenticator] with the optional [sessions] store (present
 * when browser session auth is enabled) and the pairing→replica binding lookup
 * ([replicaIdOf], used by `/sync/push` to bind pushed ops to the signing device).
 * [AllowAll] is the default for tests/dev.
 */
class ServerAuth(
    val authenticator: Authenticator,
    val sessions: SessionStore?,
    val replicaIdOf: (String) -> String? = { null },
) {
    companion object {
        val AllowAll = ServerAuth(Authenticator.AllowAll, sessions = null)
    }
}
