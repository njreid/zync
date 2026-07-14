package dev.njr.zync.server.sync

import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.authorizedOrNull
import dev.njr.zync.server.auth.requireAuth
import dev.njr.zync.server.hardening.Metrics
import dev.njr.zync.server.hardening.StorageQuota
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
 * [metrics] (if provided) counts protocol traffic for `/metrics`. [compactionFloor]
 * is the op-log compaction floor: pulls below it get **410 Gone** — the client's
 * cursor predates retained history and it must re-bootstrap (snapshot + tail).
 * [quota] (if provided) refuses pushes at 507 once the op-log byte cap is hit.
 */
fun Route.syncRoutes(
    service: SyncService,
    auth: ServerAuth,
    metrics: Metrics? = null,
    compactionFloor: () -> Long = { 0L },
    quota: StorageQuota? = null,
) {
    post("/sync/push") {
        val who = call.authorizedOrNull(auth.authenticator) ?: return@post
        if (quota != null && !quota.allowsPush()) {
            metrics?.onQuotaRejected()
            call.respondText(
                "op-log quota exceeded; ingestion paused until compaction frees space",
                status = HttpStatusCode.InsufficientStorage,
            )
            return@post
        }
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
        metrics?.onPush(request.ops.size)
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
        val floor = compactionFloor()
        if ((since ?: 0L) < floor) {
            call.respondText("op log compacted through seq $floor; re-bootstrap", status = HttpStatusCode.Gone)
            return@get
        }
        metrics?.onPull()
        call.respond(service.pull(since ?: 0L, (limit ?: SyncService.DEFAULT_PAGE).coerceAtMost(SyncService.MAX_PAGE)))
    }
    get("/sync/bootstrap") {
        if (!call.requireAuth(auth.authenticator)) return@get
        metrics?.onBootstrap()
        call.respond(service.bootstrap())
    }
    get("/health") {
        call.respondText("ok")
    }
}
