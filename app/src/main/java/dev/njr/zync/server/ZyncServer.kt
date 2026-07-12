package dev.njr.zync.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import dev.njr.zync.web.webRoutes
import kotlinx.serialization.json.Json

const val TOKEN_COOKIE = "zync_token"
const val TOKEN_HEADER = "X-Zync-Token"

/**
 * Content-Security-Policy applied to every response. The app is plain external ES modules with
 * `addEventListener`/`.onclick =` — no inline `<script>`, no inline `onclick=` attributes, no
 * inline `style=` — so `default-src 'self'` (which also governs style-src via fallback) needs no
 * `'unsafe-inline'` carve-out. `connect-src` allows `ws:` for the loopback events socket.
 *
 * `script-src` carves out `'unsafe-eval'`: the shared :web UI uses Datastar, which evaluates its
 * `data-*` expressions — a strict `default-src 'self'` blocks that (verified via the Playwright
 * csp.spec against the loopback CSP). Scripts are still self-only otherwise.
 */
const val CSP_HEADER_VALUE =
    "default-src 'self'; script-src 'self' 'unsafe-eval'; connect-src 'self' ws:; img-src 'self' data:"

/**
 * Authorizes requests on the loopback HTTP connector (the only connector the phone binds — the
 * in-app WebView talks to `127.0.0.1`). Kept as a pure, Ktor-free core so it's directly
 * unit-testable without spinning up a `testApplication`; the parts that need `ApplicationCall`
 * (reading headers/cookies) are thin adapters around this.
 */
object AuthGuard {
    /** The `?token=` -> cookie exchange only happens on the document route (never on assets/APIs). */
    fun isDocumentPath(path: String): Boolean = path == "/" || path == "/index.html"

    /**
     * The in-app WebView presents the per-boot loopback token (as a header, a `?token=` on the
     * document route, or the cookie set from it). Any request that carries the matching token is
     * authorized; everything else is rejected.
     */
    fun isAuthorized(loopbackToken: String, presentedLoopbackToken: String?): Boolean =
        presentedLoopbackToken != null && constantTimeEquals(presentedLoopbackToken, loopbackToken)
}

private fun Application.tokenGuard(token: String) {
    intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
        call.response.headers.append("Content-Security-Policy", CSP_HEADER_VALUE)

        val path = call.request.path()

        // ?token= -> cookie exchange: ONLY on the document route ("/" or "/index.html"), never
        // on /api/** or static assets — so the token never gets echoed into e.g. a JS bundle's
        // access-log line or a cached asset URL.
        val queryToken = call.request.queryParameters["token"]
        if (AuthGuard.isDocumentPath(path) && queryToken != null &&
            constantTimeEquals(queryToken, token)
        ) {
            call.response.cookies.append(TOKEN_COOKIE, token, path = "/", httpOnly = true)
        }

        // Precedence: header, then a *valid* ?token= on the document route, then the cookie. A
        // fresh, correct ?token= must win over a stale zync_token cookie left over from a
        // previous process's serverToken — otherwise cold start (new process -> new serverToken,
        // but the WebView's cookie jar still has the old one) presents the stale cookie, which
        // beats the fresh query token under a cookie-before-query ordering, and the in-app
        // WebView gets an unrecoverable 401 on the document itself. The cookie is still re-set
        // above (and so refreshed to the correct value) whenever the query token matches.
        val validQueryToken = queryToken?.takeIf {
            AuthGuard.isDocumentPath(path) && constantTimeEquals(it, token)
        }
        val presentedLoopbackToken = call.request.headers[TOKEN_HEADER]
            ?: validQueryToken
            ?: call.request.cookies[TOKEN_COOKIE]

        if (!AuthGuard.isAuthorized(token, presentedLoopbackToken)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing or invalid token"))
            finish()
        }
    }
}

fun Application.zyncModule(
    token: String,
    content: WebContent,
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = true }) }
    install(SSE)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "invalid request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "invalid request"))
        }
        // Without a logging backend AND a catch-all here, any other uncaught exception inside a
        // route handler is swallowed by Ktor and vanishes with no logcat trace at all (see
        // m1c-task-8 report) — surface it, then still answer the request rather than hanging it.
        // A generic, fixed message goes in the response body rather than the real `cause.message`.
        exception<Throwable> { call, cause ->
            android.util.Log.e("zync", "unhandled exception in route handler", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
        }
    }
    tokenGuard(token)
    routing {
        // The shared :web UI over the op log, served on the loopback.
        webRoutes(
            content.read,
            now = { System.currentTimeMillis() },
            changes = content.changes,
            commands = content.commands,
        )
    }
}

/**
 * The permanent loopback server: binds `127.0.0.1` on an ephemeral port and serves the shared
 * `:web` UI over the op log, gated by the per-boot loopback token. The in-app WebView loads
 * `http://127.0.0.1:<port>/?token=...` once at launch, so this server (and its port) must live
 * for the whole process — see [dev.njr.zync.ZyncApp.ensureServerStarted].
 */
class ZyncServer(
    private val token: String,
    private val content: WebContent,
    private val port: Int = 0,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private var httpPort: Int? = null

    /**
     * Blocks the calling thread while awaiting the server's port binding — call from a background
     * thread, not the Android main thread. Returns the resolved loopback HTTP port.
     */
    fun start(): Int {
        val e = embeddedServer(Netty, configure = {
            // Serve HTTP/1.1 only; HTTP/2 is neither needed nor safe from this Netty-on-Android
            // server (its ALPN engine can throw a fatal AssertionError during a handshake).
            enableHttp2 = false
            connector {
                this.port = this@ZyncServer.port
                host = "127.0.0.1"
            }
        }) {
            zyncModule(token, content)
        }.also { engine = it }
        e.start(wait = false)
        val resolved = kotlinx.coroutines.runBlocking { e.engine.resolvedConnectors() }
        val resolvedHttp = resolved.first { it.type == ConnectorType.HTTP }.port
        httpPort = resolvedHttp
        return resolvedHttp
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
        httpPort = null
    }
}
