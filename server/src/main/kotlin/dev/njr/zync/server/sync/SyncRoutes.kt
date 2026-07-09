package dev.njr.zync.server.sync

import dev.njr.zync.server.auth.LoginRequest
import dev.njr.zync.server.auth.LoginResponse
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.requireAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The sync protocol routes (spec §6). The push/pull/bootstrap routes are guarded by
 * [ServerAuth]; `/health` and `/auth/login` are open. Browser login is added only
 * when a session store is configured.
 */
fun Route.syncRoutes(service: SyncService, auth: ServerAuth) {
    val sessions = auth.sessions
    if (sessions != null) {
        post("/auth/login") {
            val token = sessions.login(call.receive<LoginRequest>().password, System.currentTimeMillis())
            if (token == null) call.respondText("invalid credentials", status = HttpStatusCode.Unauthorized)
            else call.respond(LoginResponse(token))
        }
    }

    post("/sync/push") {
        if (!call.requireAuth(auth.authenticator)) return@post
        call.respond(service.push(call.receive()))
    }
    get("/sync/pull") {
        if (!call.requireAuth(auth.authenticator)) return@get
        val since = call.request.queryParameters["since"]?.toLong() ?: 0L
        val limit = call.request.queryParameters["limit"]?.toLong() ?: SyncService.DEFAULT_PAGE
        call.respond(service.pull(since, limit))
    }
    get("/sync/bootstrap") {
        if (!call.requireAuth(auth.authenticator)) return@get
        call.respond(service.bootstrap())
    }
    get("/health") {
        call.respondText("ok")
    }
}
