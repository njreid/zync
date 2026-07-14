package dev.njr.zync.server.sync

import dev.njr.zync.core.sync.PushRequest
import dev.njr.zync.server.auth.ServerAuth
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
        if (!call.requireAuth(auth.authenticator)) return@post
        if (quota != null && !quota.allowsPush()) {
            metrics?.onQuotaRejected()
            call.respondText(
                "op-log quota exceeded; ingestion paused until compaction frees space",
                status = HttpStatusCode.InsufficientStorage,
            )
            return@post
        }
        val request = call.receive<PushRequest>()
        metrics?.onPush(request.ops.size)
        call.respond(service.push(request))
    }
    get("/sync/pull") {
        if (!call.requireAuth(auth.authenticator)) return@get
        val since = call.request.queryParameters["since"]?.toLong() ?: 0L
        val floor = compactionFloor()
        if (since < floor) {
            call.respondText("op log compacted through seq $floor; re-bootstrap", status = HttpStatusCode.Gone)
            return@get
        }
        val limit = call.request.queryParameters["limit"]?.toLong() ?: SyncService.DEFAULT_PAGE
        metrics?.onPull()
        call.respond(service.pull(since, limit))
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
