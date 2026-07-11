package dev.njr.zync.web

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.views.inboxSection
import dev.njr.zync.web.views.nodeDetail
import dev.njr.zync.web.views.page
import dev.njr.zync.web.views.treeSection
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.h2

/**
 * The shared content UI (M6) — server-rendered kotlinx.html over the op-log projection,
 * served identically by the central server and the phone loopback. [inbox] supplies the
 * well-known inbox node (surface-specific); [now] the current time for defer filtering.
 */
fun Route.webRoutes(
    read: ContentReadModel,
    inbox: () -> Ulid? = { null },
    now: () -> Long = { Long.MAX_VALUE },
) {
    get("/") {
        call.respondHtml { page("Inbox") { inboxSection(read, inbox(), now()) } }
    }
    get("/tree") {
        call.respondHtml { page("Tree") { h2 { +"Tree" }; treeSection(read, null) } }
    }
    get("/node/{id}") {
        val node = call.parameters["id"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }?.let(read::node)
        if (node == null) {
            call.respondText("not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondHtml { page(node.title ?: "Node") { nodeDetail(read, node) } }
        }
    }
    get("/health") {
        call.respondText("ok")
    }
}
