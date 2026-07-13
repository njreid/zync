package dev.njr.zync.server.auth

/**
 * Bundles the request [authenticator] with the optional [sessions] store (present
 * when browser session auth is enabled). [AllowAll] is the default for tests/dev.
 */
class ServerAuth(val authenticator: Authenticator, val sessions: SessionStore?) {
    companion object {
        val AllowAll = ServerAuth(Authenticator.AllowAll, sessions = null)
    }
}
