package dev.njr.zync.web

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.sse.ChangeNotifier
import dev.njr.zync.web.sse.patch
import dev.njr.zync.web.sse.patchElementsEvent
import dev.njr.zync.web.sse.respondDatastar
import dev.njr.zync.web.views.inboxSection
import dev.njr.zync.web.views.nodeDetail
import dev.njr.zync.web.views.page
import dev.njr.zync.web.views.readingView
import dev.njr.zync.web.views.treeSection
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
    commands: ContentCommands? = null,
) {
    get("/") {
        call.respondHtml {
            page("Inbox") {
                div {
                    id = "inbox"
                    // Datastar: open the SSE stream on load; the server patches #inbox on change.
                    attributes["data-on:load"] = "@get('/updates')"
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
            call.respondHtml { page(node.title ?: "Node") { div { id = "node-detail"; nodeDetail(read, node) } } }
        }
    }
    get("/node/{id}/read") {
        val node = call.parameters["id"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }?.let(read::node)
        if (node == null) call.respondText("not found", status = HttpStatusCode.NotFound)
        else call.respondHtml { page(node.title ?: "Read") { readingView(node) } }
    }
    get("/assets/datastar.js") {
        call.respondText(WebPlatform.datastarRuntime(), ContentType("application", "javascript"))
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

    if (commands != null) {
        suspend fun ApplicationCall.applied(mutate: ContentCommands.() -> Unit) {
            commands.mutate()
            changes?.notifyChanged()
            respondDatastar(patchElementsEvent(WebPlatform.renderFragment("inbox") { inboxSection(read, inbox(), now()) }))
        }
        fun ApplicationCall.nodeId(): Ulid? = parameters["id"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }

        post("/inbox") {
            val title = call.request.queryParameters["title"]?.trim().orEmpty()
            if (title.isEmpty()) call.respondText("title required", status = HttpStatusCode.BadRequest)
            else call.applied { createTask(title, inbox()) }
        }
        post("/node/{id}/complete") { call.nodeId()?.let { id -> call.applied { complete(id) } } }
        post("/node/{id}/reopen") { call.nodeId()?.let { id -> call.applied { reopen(id) } } }
        post("/node/{id}/trash") { call.nodeId()?.let { id -> call.applied { trash(id) } } }
        // Agent-proposal review (spec §8): accept/reject are human ops.
        post("/proposal/{id}/accept") { call.nodeId()?.let { id -> call.applied { acceptProposal(id) } } }
        post("/proposal/{id}/reject") { call.nodeId()?.let { id -> call.applied { rejectProposal(id) } } }
        post("/node/{id}/defer") {
            val until = call.request.queryParameters["until"]?.toLongOrNull() ?: 0L
            call.nodeId()?.let { id -> call.applied { defer(id, until) } }
        }
        post("/node/{id}/move") {
            val parent = call.request.queryParameters["parent"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }
            if (parent != null) call.nodeId()?.let { id -> call.applied { move(id, parent) } }
        }

        // Detail-page actions patch #node-detail (not the inbox).
        suspend fun ApplicationCall.appliedDetail(id: Ulid, mutate: ContentCommands.() -> Unit) {
            commands.mutate()
            changes?.notifyChanged()
            val node = read.node(id) ?: return respondText("not found", status = HttpStatusCode.NotFound)
            respondDatastar(patchElementsEvent(WebPlatform.renderFragment("node-detail") { nodeDetail(read, node) }))
        }
        post("/node/{id}/subtask") {
            val title = call.request.queryParameters["title"]?.trim().orEmpty()
            val id = call.nodeId()
            if (id != null && title.isNotEmpty()) call.appliedDetail(id) { addSubtask(id, title) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        post("/node/{id}/comment") {
            val text = call.request.queryParameters["text"]?.trim().orEmpty()
            val id = call.nodeId()
            if (id != null && text.isNotEmpty()) call.appliedDetail(id) { addComment(id, text) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
    }
}
