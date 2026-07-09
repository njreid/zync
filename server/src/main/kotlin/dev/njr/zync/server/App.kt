package dev.njr.zync.server

import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.server.sync.syncRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** The zync server Ktor module: JSON negotiation, error mapping, and sync routes. */
fun Application.zyncModule(service: SyncService, auth: ServerAuth = ServerAuth.AllowAll, json: Json = Json) {
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(cause.message ?: "internal error", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        syncRoutes(service, auth)
    }
}
