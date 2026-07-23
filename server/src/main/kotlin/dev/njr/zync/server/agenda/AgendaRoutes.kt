package dev.njr.zync.server.agenda

import dev.njr.zync.core.agenda.AgendaEventDto
import dev.njr.zync.core.agenda.AgendaPush
import dev.njr.zync.core.agenda.AgendaSnapshot
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.bearerToken
import dev.njr.zync.server.auth.constantTimeEquals
import dev.njr.zync.server.auth.requireAuth
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Caps: one push replaces a source wholesale, so bound the damage a bad pusher can do. */
private const val MAX_EVENTS = 500
private const val MAX_TITLE = 300
private const val LOOKBACK_MS = 24L * 60 * 60_000

/** Bundles the agenda channel's dependencies for wiring into the app module. */
class AgendaEndpoint(val db: ZyncDatabase, val ingestToken: String?)

/**
 * The agenda side channel (build-order #4): `POST /agenda/{source}` — bearer
 * ingest-token gated (ZYNC_AGENDA_TOKEN; hidden when unset) — replaces that
 * source's events wholesale. `GET /agenda` — device-signed, like /sync — returns
 * all sources' upcoming events for the phone to merge into its agenda.
 */
fun Route.agendaRoutes(endpoint: AgendaEndpoint, auth: ServerAuth, now: () -> Long = System::currentTimeMillis) {
    val queries = endpoint.db.agendaEventQueries

    post("/agenda/{source}") {
        val token = endpoint.ingestToken
        val presented = bearerToken(call.request.headers[HttpHeaders.Authorization])
        if (token == null || presented == null || !constantTimeEquals(presented, token)) {
            call.respondText("agenda ingestion not authorized", status = HttpStatusCode.Unauthorized)
            return@post
        }
        val source = call.parameters["source"]?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: run { call.respondText("bad source", status = HttpStatusCode.BadRequest); return@post }
        val push = call.receive<AgendaPush>()
        if (push.events.size > MAX_EVENTS) {
            call.respondText("too many events (max $MAX_EVENTS)", status = HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val bad = push.events.firstOrNull { it.title.isBlank() || it.title.length > MAX_TITLE || it.endMillis <= it.beginMillis }
        if (bad != null) {
            call.respondText("invalid event: ${bad.title.take(40)}", status = HttpStatusCode.BadRequest)
            return@post
        }
        endpoint.db.transaction {
            queries.deleteSource(source)
            push.events.forEach {
                queries.insertEvent(source, it.title, it.beginMillis, it.endMillis, if (it.allDay) 1 else 0, it.profile, it.location?.take(500), it.link?.take(1000))
            }
            queries.prune(now() - LOOKBACK_MS)
        }
        call.respond(mapOf("accepted" to push.events.size))
    }

    get("/agenda") {
        if (!call.requireAuth(auth.authenticator)) return@get
        val events = queries.upcoming(now() - LOOKBACK_MS).executeAsList().map {
            AgendaEventDto(it.title, it.begin_ms, it.end_ms, it.all_day != 0L, it.profile, it.location, it.link)
        }
        call.respond(AgendaSnapshot(events))
    }
}
