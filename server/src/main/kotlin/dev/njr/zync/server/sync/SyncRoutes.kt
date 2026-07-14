package dev.njr.zync.server.sync

import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.authorizedOrNull
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
 * [ServerAuth]; `/health` is open. Browser session auth lives in `webauthn.webAuthnRoutes`.
 */
fun Route.syncRoutes(service: SyncService, auth: ServerAuth) {
    post("/sync/push") {
        val who = call.authorizedOrNull(auth.authenticator) ?: return@post
        val request = call.receive<PushRequest>()
        // Bind pushed ops to the signing device: every op's authoring replica id must
        // equal the id bound to this pairing. Browser sessions and the dev AllowAll
        // authenticator carry no device identity and are exempt.
        val signingDevice = who.deviceId
        if (signingDevice != null) {
            val bound = auth.replicaIdOf(signingDevice)
            if (bound == null) {
                call.respondText(
                    "device has no bound replica id (paired before binding existed) — re-pair",
                    status = HttpStatusCode.Forbidden,
                )
                return@post
            }
            val foreign = request.ops.firstOrNull { it.deviceId != bound }
            if (foreign != null) {
                call.respondText(
                    "op ${foreign.opId} claims deviceId '${foreign.deviceId}' but this pairing is bound to '$bound'",
                    status = HttpStatusCode.Forbidden,
                )
                return@post
            }
        }
        call.respond(service.push(request))
    }
    get("/sync/pull") {
        if (!call.requireAuth(auth.authenticator)) return@get
        val since = call.request.queryParameters["since"]?.toLongOrNull()?.takeIf { it >= 0 }
        val limit = call.request.queryParameters["limit"]?.toLongOrNull()?.takeIf { it > 0 }
        if (since == null && call.request.queryParameters.contains("since")) {
            call.respondText("invalid 'since': expected a non-negative integer", status = HttpStatusCode.BadRequest)
            return@get
        }
        if (limit == null && call.request.queryParameters.contains("limit")) {
            call.respondText("invalid 'limit': expected a positive integer", status = HttpStatusCode.BadRequest)
            return@get
        }
        call.respond(service.pull(since ?: 0L, (limit ?: SyncService.DEFAULT_PAGE).coerceAtMost(SyncService.MAX_PAGE)))
    }
    get("/sync/bootstrap") {
        if (!call.requireAuth(auth.authenticator)) return@get
        call.respond(service.bootstrap())
    }
    get("/health") {
        call.respondText("ok")
    }
}
