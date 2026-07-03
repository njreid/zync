package dev.njr.zync.server

import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
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

private fun Application.tokenGuard(token: String) {
    intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
        val presented = call.request.headers[TOKEN_HEADER]
            ?: call.request.queryParameters["token"]
            ?: call.request.cookies[TOKEN_COOKIE]
        if (presented != token) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing or invalid token"))
            finish()
        } else if (call.request.queryParameters["token"] == token) {
            call.response.cookies.append(TOKEN_COOKIE, token, path = "/", httpOnly = true)
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
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = true }) }
    install(WebSockets)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "invalid request"))
        }
    }
    tokenGuard(token)
    routing {
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
            zyncModule(db, repo, token, assets)
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

