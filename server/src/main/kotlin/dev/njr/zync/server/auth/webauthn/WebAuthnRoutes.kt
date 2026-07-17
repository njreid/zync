package dev.njr.zync.server.auth.webauthn

import dev.njr.zync.server.auth.SESSION_COOKIE
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.auth.constantTimeEquals
import dev.njr.zync.server.auth.sessionToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Bundles the [WebAuthnService] with the [sessions] it mints into and the optional
 * [registrationToken] that gates passkey enrolment (`ZYNC_WEBAUTHN_REG_TOKEN`). When the
 * token is null, registration is disabled entirely.
 */
class WebAuthnEndpoint(
    val service: WebAuthnService,
    val sessions: SessionStore,
    val registrationToken: String?,
)

/**
 * Browser passkey auth. Registration is gated by `X-Registration-Token`; assertion is open
 * (that's the whole point — passwordless login) and mints a session on success. The paths
 * live under `/auth/` so the `:web` session gate lets them through unauthenticated.
 */
fun Route.webAuthnRoutes(endpoint: WebAuthnEndpoint, now: () -> Long = System::currentTimeMillis) {
    val service = endpoint.service
    route("/auth/webauthn") {
        get("/register/options") {
            if (!registrationAllowed(call, endpoint)) return@get
            call.respond(service.registrationOptions())
        }
        post("/register") {
            if (!registrationAllowed(call, endpoint)) return@post
            if (service.verifyRegistration(call.receive())) call.respondText("ok")
            else call.respondText("registration failed", status = HttpStatusCode.BadRequest)
        }
        get("/assert/options") { call.respond(service.assertionOptions()) }
        post("/assert") {
            if (!service.verifyAssertion(call.receive())) {
                call.respondText("assertion failed", status = HttpStatusCode.Unauthorized)
                return@post
            }
            val token = endpoint.sessions.mint(now())
            call.response.cookies.append(SESSION_COOKIE, token, path = "/", httpOnly = true)
            call.respond(SessionResponse(token))
        }
    }
    post("/auth/logout") {
        sessionToken(call)?.let { endpoint.sessions.logout(it) }
        call.respondText("ok")
    }
    get("/login") { call.respondText(loginPageHtml(), ContentType.Text.Html) }
}

private suspend fun registrationAllowed(call: ApplicationCall, endpoint: WebAuthnEndpoint): Boolean {
    val required = endpoint.registrationToken
    if (required == null) {
        call.respondText("registration disabled", status = HttpStatusCode.Forbidden)
        return false
    }
    val presented = call.request.headers["X-Registration-Token"]
    if (presented == null || !constantTimeEquals(presented, required)) {
        call.respondText("invalid registration token", status = HttpStatusCode.Forbidden)
        return false
    }
    return true
}

/**
 * Paths that never require a browser session: the auth ceremony, the login page, static
 * assets, health, and the device/sync/pairing APIs (which carry their own auth). Everything
 * else — the `:web` document, its data routes, and its mutation POSTs — needs a session.
 */
private val SESSION_EXEMPT = listOf("/auth", "/login", "/assets", "/health", "/sync", "/debug", "/blob", "/metrics", "/pair", "/favicon")

/**
 * Gate the server-hosted `:web` UI behind a browser session: an unauthenticated document
 * request is redirected to `/login`; any other unauthenticated `:web` request gets a 401.
 * Installed only when browser session auth is configured (never in dev/AllowAll).
 */
fun io.ktor.server.application.Application.installWebSessionGate(
    sessions: SessionStore,
    now: () -> Long = System::currentTimeMillis,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        // Device/operator APIs (/sync, /blob, /pair, /debug, …) carry their own per-route auth and
        // are exempt by PATH. Do NOT exempt by a client-supplied header (e.g. X-Device-Id) — the
        // :web routes have no other guard, so a header-based bypass would open all content.
        if (SESSION_EXEMPT.any { path == it || path.startsWith("$it/") }) return@intercept
        val token = sessionToken(call)
        if (token == null || !sessions.validate(token, now())) {
            // Browser NAVIGATIONS (Accept: text/html) bounce to /login — sessions are
            // in-memory, so after a redeploy a tapped nav link (e.g. /settings/pairing)
            // must re-auth, not dead-end on a bare 401 page. API/SSE calls still 401.
            val wantsHtml = call.request.headers[io.ktor.http.HttpHeaders.Accept]?.contains("text/html") == true
            if (call.request.local.method == io.ktor.http.HttpMethod.Get && wantsHtml) {
                call.respondRedirect("/login")
            } else {
                call.respond(HttpStatusCode.Unauthorized)
            }
            finish()
        }
    }
}
