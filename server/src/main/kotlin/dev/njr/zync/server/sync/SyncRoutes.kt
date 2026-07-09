package dev.njr.zync.server.sync

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The sync protocol routes (spec §6). Auth is layered on in Task 4. */
fun Route.syncRoutes(service: SyncService) {
    post("/sync/push") {
        call.respond(service.push(call.receive()))
    }
    get("/sync/pull") {
        val since = call.request.queryParameters["since"]?.toLong() ?: 0L
        val limit = call.request.queryParameters["limit"]?.toLong() ?: SyncService.DEFAULT_PAGE
        call.respond(service.pull(since, limit))
    }
    get("/sync/bootstrap") {
        call.respond(service.bootstrap())
    }
    get("/health") {
        call.respondText("ok")
    }
}
