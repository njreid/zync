package dev.njr.zync.server.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.security.MessageDigest

/** Cookie carrying the browser session bearer token (set after a verified WebAuthn assertion). */
const val SESSION_COOKIE = "zync_session"

/** The browser session token, from the session cookie or an `Authorization: Bearer` header. */
fun sessionToken(call: ApplicationCall): String? =
    call.request.cookies[SESSION_COOKIE]
        ?: call.request.header("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()

internal fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
