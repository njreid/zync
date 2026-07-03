package dev.njr.zync.server

import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.Crypto
import dev.njr.zync.pairing.PairingService
import io.ktor.http.ContentType
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
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.security.KeyStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.Json

const val TOKEN_COOKIE = "zync_token"
const val TOKEN_HEADER = "X-Zync-Token"

/** Bearer-or-cookie carrier for a LAN session token issued by [PairingService.issueSession]. */
const val SESSION_COOKIE = "zync_session"

/**
 * Content-Security-Policy applied to every response. The app is plain external ES modules with
 * `addEventListener`/`.onclick =` — no inline `<script>`, no inline `onclick=` attributes, no
 * inline `style=` — so `default-src 'self'` (which also governs script-src/style-src via
 * fallback) needs no `'unsafe-inline'` carve-out. `connect-src` allows both `ws:`/`wss:` for the
 * events socket (the loopback connector is plain `ws:`, the LAN connector is `wss:`).
 */
const val CSP_HEADER_VALUE =
    "default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:"

/**
 * Distinguishes and authorizes requests arriving on the two connectors the server can be bound
 * to (see [LanConfig]): the loopback HTTP connector (used by the in-app WebView) and the LAN
 * HTTPS connector (used by paired desktop clients). Kept as a pure, Ktor-free core so it's
 * directly unit-testable without spinning up a `testApplication` — the parts that *do* need
 * `ApplicationCall` (reading headers/cookies, inspecting `call.request.local.scheme`) are thin
 * adapters around this.
 */
object AuthGuard {
    enum class Connector { LOOPBACK, LAN }

    /**
     * The loopback connector is always plain HTTP; the LAN connector (see [ZyncServer.start]) is
     * always TLS via `sslConnector`. `call.request.local.scheme` reflects the connector the
     * socket was actually accepted on (unlike `call.request.origin`, it isn't influenced by
     * forwarded-proto headers), so it's a reliable way to tell the two apart.
     */
    fun classify(localScheme: String): Connector =
        if (localScheme.equals("https", ignoreCase = true)) Connector.LAN else Connector.LOOPBACK

    /** Pairing/session-bootstrap routes are unauthenticated by design (see `PairingService`). */
    fun isPairingPath(path: String): Boolean = path.trimStart('/').startsWith("pair/")

    fun isDocumentPath(path: String): Boolean = path == "/" || path == "/index.html"

    /**
     * Pure authorization decision:
     *  - on the loopback connector, either the per-boot loopback token or a valid LAN session
     *    token is accepted (the in-app WebView only ever has the loopback token; a session token
     *    is also accepted here so a device that's already paired can be exercised the same way
     *    over loopback in tests/tooling).
     *  - on the LAN connector, only a valid session token is accepted — the loopback token must
     *    never be honored off-device.
     *
     * [isValidSession] is passed in (rather than calling `PairingService` directly) purely so
     * this function can be unit tested without any Ktor or Room dependency.
     */
    suspend fun isAuthorized(
        connector: Connector,
        loopbackToken: String,
        presentedLoopbackToken: String?,
        sessionToken: String?,
        isValidSession: suspend (String) -> Boolean,
    ): Boolean {
        val sessionOk = sessionToken != null && isValidSession(sessionToken)
        if (sessionOk) return true
        return connector == Connector.LOOPBACK &&
            presentedLoopbackToken != null &&
            Crypto.constantTimeEquals(presentedLoopbackToken, loopbackToken)
    }
}

private fun Application.tokenGuard(token: String, pairing: PairingService?) {
    intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
        call.response.headers.append("Content-Security-Policy", CSP_HEADER_VALUE)

        val path = call.request.path()
        if (AuthGuard.isPairingPath(path)) return@intercept

        val connector = AuthGuard.classify(call.request.local.scheme)

        // ?token= -> cookie exchange: ONLY on the document route ("/" or "/index.html"), never
        // on /api/** or static assets — so the token never gets echoed into e.g. a JS bundle's
        // access-log line or a cached asset URL.
        val queryToken = call.request.queryParameters["token"]
        if (AuthGuard.isDocumentPath(path) && queryToken != null &&
            Crypto.constantTimeEquals(queryToken, token)
        ) {
            call.response.cookies.append(TOKEN_COOKIE, token, path = "/", httpOnly = true)
        }

        val presentedLoopbackToken = call.request.headers[TOKEN_HEADER]
            ?: call.request.cookies[TOKEN_COOKIE]
            ?: queryToken?.takeIf { AuthGuard.isDocumentPath(path) }

        val sessionToken = call.request.headers[io.ktor.http.HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
            ?: call.request.cookies[SESSION_COOKIE]

        val authorized = AuthGuard.isAuthorized(
            connector = connector,
            loopbackToken = token,
            presentedLoopbackToken = presentedLoopbackToken,
            sessionToken = sessionToken,
            isValidSession = { t -> pairing?.validateSession(t) ?: false },
        )

        if (!authorized) {
            if (connector == AuthGuard.Connector.LAN) {
                // Zero body: a LAN attacker gets no hint of *why* the request failed.
                call.respond(HttpStatusCode.Forbidden)
            } else {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing or invalid token"))
            }
            finish()
        }
    }
}

@OptIn(FlowPreview::class)
fun ZyncDatabase.changesFlow(): Flow<Unit> =
    callbackFlow {
        val observer = object : androidx.room.InvalidationTracker.Observer(
            arrayOf("node", "context", "node_context")
        ) {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        invalidationTracker.addObserver(observer)
        awaitClose { invalidationTracker.removeObserver(observer) }
    }.debounce(100)

fun Application.zyncModule(
    db: ZyncDatabase,
    repo: NodeRepository,
    token: String,
    assets: (String) -> Pair<ByteArray, ContentType>?,
    pairing: PairingService? = null,
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = true }) }
    install(WebSockets)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "invalid request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "invalid request"))
        }
    }
    tokenGuard(token, pairing)
    routing {
        if (pairing != null) pairingRoutes(pairing)
        apiRoutes(db, repo)
        get("/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            if (segments.any { it == ".." || it.contains('\u0000') }) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not found"))
                return@get
            }
            val path = segments.joinToString("/").ifEmpty { "index.html" }
            val hit = assets(path)
            if (hit == null) call.respond(HttpStatusCode.NotFound, ErrorDto("not found"))
            else call.respondBytes(hit.first, hit.second)
        }
    }
}

/**
 * Configuration for an additional LAN-facing HTTPS connector, served alongside the loopback
 * HTTP connector. [keyStore] must already contain [keyAlias] (see
 * `Crypto.generateSelfSignedCert` / `ServerIdentity`).
 */
data class LanConfig(
    val keyStore: KeyStore,
    val keyStorePassword: CharArray,
    val keyAlias: String,
    val host: String,
    val tlsPort: Int = 0,
)

class ZyncServer(
    private val db: ZyncDatabase,
    private val repo: NodeRepository,
    private val token: String,
    private val assets: (String) -> Pair<ByteArray, ContentType>?,
    private val port: Int = 0,
    private val lan: LanConfig? = null,
    private val pairing: PairingService? = null,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private var httpPort: Int? = null
    private var httpsPort: Int? = null

    /**
     * Blocks the calling thread while awaiting the server's port binding — call from a
     * background thread, not the Android main thread.
     *
     * Returns the loopback HTTP port. When a [LanConfig] is supplied, a second HTTPS connector
     * is also bound on `lan.host`/`lan.tlsPort`; its resolved port is available via [tlsPort]
     * once this call returns.
     */
    fun start(): Int {
        val currentLan = lan
        val e = embeddedServer(Netty, configure = {
            connector {
                this.port = this@ZyncServer.port
                host = "127.0.0.1"
            }
            if (currentLan != null) {
                sslConnector(
                    keyStore = currentLan.keyStore,
                    keyAlias = currentLan.keyAlias,
                    keyStorePassword = { currentLan.keyStorePassword },
                    privateKeyPassword = { currentLan.keyStorePassword },
                ) {
                    this.port = currentLan.tlsPort
                    host = currentLan.host
                }
            }
        }) {
            zyncModule(db, repo, token, assets, pairing)
        }.also { engine = it }
        e.start(wait = false)
        val resolved = kotlinx.coroutines.runBlocking { e.engine.resolvedConnectors() }
        val resolvedHttp = resolved.first { it.type == ConnectorType.HTTP }.port
        httpPort = resolvedHttp
        httpsPort = resolved.firstOrNull { it.type == ConnectorType.HTTPS }?.port
        return resolvedHttp
    }

    /** The resolved LAN HTTPS port, or `null` if this server was started without a [LanConfig]. */
    fun tlsPort(): Int? = httpsPort

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
        httpPort = null
        httpsPort = null
    }
}

