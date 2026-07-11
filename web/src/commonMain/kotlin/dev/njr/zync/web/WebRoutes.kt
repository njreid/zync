package dev.njr.zync.web

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.sse.ChangeNotifier
import dev.njr.zync.web.sse.patch
import dev.njr.zync.web.sse.patchElementsEvent
import dev.njr.zync.web.views.inboxSection
import dev.njr.zync.web.views.nodeDetail
import dev.njr.zync.web.views.page
import dev.njr.zync.web.views.treeSection
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.sse
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.id

/**
 * The shared content UI (M6), served identically by the central server and the phone
 * loopback. [inbox] supplies the well-known inbox node; [now] the time for defer
 * filtering; [changes] (if provided) drives live Datastar SSE updates — the caller must
 * `install(SSE)` and call [ChangeNotifier.notifyChanged] after applying ops.
 */
fun Route.webRoutes(
    read: ContentReadModel,
    inbox: () -> Ulid? = { null },
    now: () -> Long = { Long.MAX_VALUE },
    changes: ChangeNotifier? = null,
) {
    get("/") {
        call.respondHtml {
            page("Inbox") {
                div {
                    id = "inbox"
                    // Datastar: open the SSE stream on load; the server patches #inbox on change.
                    attributes["data-on-load"] = "@get('/updates')"
                    inboxSection(read, inbox(), now())
                }
            }
        }
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
    get("/assets/datastar.js") {
        call.respondText(WebPlatform.datastarRuntime(), ContentType("application", "javascript"))
    }
    get("/health") {
        call.respondText("ok")
    }

    if (changes != null) {
        sse("/updates") {
            suspend fun pushInbox() = patch(
                patchElementsEvent(WebPlatform.renderFragment("inbox") { inboxSection(read, inbox(), now()) }),
            )
            pushInbox()
            changes.changes.collect { pushInbox() }
        }
    }
}
