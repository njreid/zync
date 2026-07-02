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
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
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
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                .ifEmpty { "index.html" }
            val hit = assets(path)
            if (hit == null) call.respond(HttpStatusCode.NotFound, ErrorDto("not found"))
            else call.respondBytes(hit.first, hit.second)
        }
    }
}

class ZyncServer(
    private val db: ZyncDatabase,
    private val repo: NodeRepository,
    private val token: String,
    private val assets: (String) -> Pair<ByteArray, ContentType>?,
    private val port: Int = 0,
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start(): Int {
        val e = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            zyncModule(db, repo, token, assets)
        }.also { engine = it }
        e.start(wait = false)
        return kotlinx.coroutines.runBlocking { e.engine.resolvedConnectors().first().port }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }
}

