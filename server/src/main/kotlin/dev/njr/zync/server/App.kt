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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
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
    json: Json = Json,
) {
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(cause.message ?: "internal error", status = HttpStatusCode.InternalServerError)
        }
    }
    if (hardening != null) installHardening(hardening)
    if (content != null) install(SSE)
    // When browser passkey auth is configured, the server-hosted :web UI is gated behind a session.
    if (content != null && webauthn != null) installWebSessionGate(webauthn.sessions)
    if (content != null && webauthn == null) {
        // Fail-loud: the browser :web UI (incl. mutation POSTs) is being served with no session
        // gate. Intended only for the dev/AllowAll server; a production deploy must set the
        // ZYNC_WEBAUTHN_* env vars (see ServerConfig.buildWebAuthn) so passkey auth is enforced.
        log.warn("Serving :web WITHOUT browser auth — set ZYNC_WEBAUTHN_RP_ID/_ORIGIN to gate it")
    }
    routing {
        syncRoutes(service, auth)
        debugRoutes(service, auth)
        if (blobs != null) blobRoutes(blobs, auth)
        if (pairing != null) pairingRoutes(pairing.manager, pairing.identity)
        if (webauthn != null) webAuthnRoutes(webauthn)
        if (hardening != null) get("/metrics") {
            if (!call.requireAuth(auth.authenticator)) return@get
            call.respond(hardening.metrics.snapshot())
        }
        if (content != null) webRoutes(content.read, changes = content.changes, commands = content.commands)
    }
}
