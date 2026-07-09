package dev.njr.zync.server.auth

import kotlinx.serialization.Serializable

/**
 * Bundles the request [authenticator] with the optional [sessions] store (present
 * when browser login is enabled). [AllowAll] is the default for tests/dev.
 */
class ServerAuth(val authenticator: Authenticator, val sessions: SessionStore?) {
    companion object {
        val AllowAll = ServerAuth(Authenticator.AllowAll, sessions = null)
    }
}

@Serializable
data class LoginRequest(val password: String)

@Serializable
data class LoginResponse(val token: String)
