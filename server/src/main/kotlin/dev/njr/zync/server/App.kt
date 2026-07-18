package dev.njr.zync.server

import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.requireAuth
import dev.njr.zync.server.auth.webauthn.WebAuthnEndpoint
import dev.njr.zync.server.auth.webauthn.installWebSessionGate
import dev.njr.zync.server.auth.webauthn.webAuthnRoutes
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.blobRoutes
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.web.webRoutes
import io.ktor.server.sse.SSE
import dev.njr.zync.server.debug.debugRoutes
import dev.njr.zync.server.hardening.Hardening
import dev.njr.zync.server.hardening.StorageQuota
import dev.njr.zync.server.hardening.UsageGauges
import dev.njr.zync.server.hardening.installHardening
import dev.njr.zync.server.pairing.PairingEndpoint
import dev.njr.zync.server.pairing.pairingRoutes
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.server.sync.syncRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import dev.njr.zync.server.agenda.AgendaEndpoint
import dev.njr.zync.server.agenda.agendaRoutes
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** The zync server Ktor module: JSON negotiation, error mapping, and sync routes. */
fun Application.zyncModule(
    service: SyncService,
    auth: ServerAuth = ServerAuth.AllowAll,
    blobs: BlobService? = null,
    hardening: Hardening? = null,
    pairing: PairingEndpoint? = null,
    content: ServerContent? = null,
    webauthn: WebAuthnEndpoint? = null,
    agenda: AgendaEndpoint? = null,
    json: Json = Json,
    allowUnauthenticatedWeb: Boolean = false,
    usage: () -> UsageGauges = { UsageGauges() },
    compactionFloor: () -> Long = { 0L },
    quota: StorageQuota? = null,
) {
    install(ContentNegotiation) { json(json) }
    // Device auth hashes the request body into the signed canonical string, so the
    // authenticator consumes the body before the route does.
    install(DoubleReceive)
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.application.log.info("bad request for ${call.request.path()}: ${cause.message}")
            call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, cause ->
            // Internal detail (paths, SQL, library messages) must not reach clients.
            call.application.log.error("unhandled exception for ${call.request.path()}", cause)
            call.respondText("internal error", status = HttpStatusCode.InternalServerError)
        }
    }
    if (hardening != null) installHardening(hardening)
    if (content != null) install(SSE)
    // When browser passkey auth is configured, the server-hosted :web UI is gated behind a session.
    if (content != null && webauthn != null) installWebSessionGate(webauthn.sessions)
    if (content != null && webauthn == null) {
        // Fail closed: serving the browser :web UI (incl. mutation POSTs) with no session gate
        // must be an explicit dev-only decision, never the result of a missing/typo'd
        // ZYNC_WEBAUTHN_* env var on a production deploy (see ServerConfig.buildWebAuthn).
        check(allowUnauthenticatedWeb) {
            "refusing to serve :web without browser auth — set ZYNC_WEBAUTHN_RP_ID/_ORIGIN, " +
                "or ZYNC_ALLOW_UNAUTHENTICATED_WEB=true for a dev server"
        }
        log.warn("Serving :web WITHOUT browser auth — set ZYNC_WEBAUTHN_RP_ID/_ORIGIN to gate it")
    }
    routing {
        syncRoutes(service, auth, metrics = hardening?.metrics, compactionFloor = compactionFloor, quota = quota)
        debugRoutes(service, auth)
        if (blobs != null) blobRoutes(blobs, auth, metrics = hardening?.metrics)
        if (pairing != null) pairingRoutes(pairing.manager, pairing.identity, publicAddress = pairing.publicAddress)
        if (webauthn != null) webAuthnRoutes(webauthn)
        if (agenda != null) agendaRoutes(agenda, auth)
        if (hardening != null) get("/metrics") {
            if (!call.requireAuth(auth.authenticator)) return@get
            call.respond(hardening.metrics.snapshot(usage()))
        }
        if (content != null) {
            val pairingPage = if (pairing?.publicAddress != null) "/settings/pairing" else null
            webRoutes(content.read, changes = content.changes, commands = content.commands, settingsHref = pairingPage)
        }
    }
}
