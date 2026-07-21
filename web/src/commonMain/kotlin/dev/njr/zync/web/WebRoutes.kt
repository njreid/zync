package dev.njr.zync.web

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.Reorder
import dev.njr.zync.web.sse.ChangeNotifier
import dev.njr.zync.web.sse.patch
import dev.njr.zync.web.sse.patchElementsEvent
import dev.njr.zync.web.sse.respondDatastar
import dev.njr.zync.web.views.Tab
import dev.njr.zync.web.views.inboxSection
import dev.njr.zync.web.views.nextSection
import dev.njr.zync.web.views.nodeDetail
import dev.njr.zync.web.views.page
import dev.njr.zync.web.views.projectsSection
import dev.njr.zync.web.views.readingView
import dev.njr.zync.web.views.referenceResults
import dev.njr.zync.web.views.referenceSection
import dev.njr.zync.web.views.treeSection
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondBytes
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
/** The context selected for this surface: `?context=` wins, else the cookie; blank/none = all. */
private fun ApplicationCall.selectedContext(): Ulid? {
    val raw = request.queryParameters["context"] ?: request.cookies["zync_context"]
    if (raw.isNullOrBlank() || raw == "none") return null
    return runCatching { Ulid.parse(raw) }.getOrNull()
}

fun Route.webRoutes(
    read: ContentReadModel,
    inbox: () -> Ulid? = { null },
    now: () -> Long = { Long.MAX_VALUE },
    changes: ChangeNotifier? = null,
    commands: ContentCommands? = null,
    /** Nav link to the pairing/settings page — server-only (null on the phone loopback). */
    settingsHref: String? = null,
) {
    get("/") {
        // ?context=<id> selects a context (persisted in a cookie so mutations and the
        // SSE stream see it); ?context=none clears it. Switching is a navigation, so
        // the SSE stream reopens already filtered.
        call.request.queryParameters["context"]?.let { chosen ->
            call.response.cookies.append(
                "zync_context",
                if (chosen == "none") "" else chosen,
                path = "/",
                maxAge = 365L * 24 * 60 * 60,
            )
        }
        val context = call.selectedContext()
        call.respondHtml {
            page("Inbox", settingsHref, Tab.INBOX) {
                div {
                    id = "inbox"
                    // Datastar: open the SSE stream on load; the server patches #inbox on change.
                    attributes["data-on:load"] = "@get('/updates')"
                    inboxSection(read, inbox(), now(), context)
                }
            }
        }
    }
    get("/next") {
        // ?context= selects a context (persisted in a cookie), mirroring "/".
        call.request.queryParameters["context"]?.let { chosen ->
            call.response.cookies.append(
                "zync_context",
                if (chosen == "none") "" else chosen,
                path = "/",
                maxAge = 365L * 24 * 60 * 60,
            )
        }
        val context = call.selectedContext()
        call.respondHtml {
            page("Next", settingsHref, Tab.NEXT) {
                div {
                    id = "next"
                    // Live-refresh this surface as tasks complete/defer elsewhere.
                    attributes["data-on:load"] = "@get('/updates/next')"
                    nextSection(read, inbox(), now(), context)
                }
            }
        }
    }
    get("/projects") {
        call.respondHtml {
            page("Projects", settingsHref, Tab.PROJECTS) {
                div {
                    id = "projects"
                    attributes["data-on:load"] = "@get('/updates/projects')"
                    projectsSection(read, now(), inbox())
                }
            }
        }
    }
    get("/reference") {
        call.respondHtml {
            page("Reference", settingsHref, Tab.REFERENCE) {
                div {
                    id = "reference"
                    attributes["data-on:load"] = "@get('/updates/reference')"
                    referenceSection(read, null)
                }
            }
        }
    }
    get("/reference/search") {
        // A read (works even without the commands block); patches #reference-results.
        val q = call.request.queryParameters["q"]
        call.respondDatastar(patchElementsEvent(WebPlatform.renderFragment("reference-results") { referenceResults(read, q) }))
    }
    get("/tree") {
        call.respondHtml { page("Tree", settingsHref, Tab.PROJECTS) { h2 { +"Tree" }; treeSection(read, null) } }
    }
    get("/node/{id}") {
        val node = call.parameters["id"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }?.let(read::node)
        if (node == null) {
            call.respondText("not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondHtml { page(node.title ?: "Node", settingsHref, Tab.NONE) { div { id = "node-detail"; nodeDetail(read, node) } } }
        }
    }
    get("/node/{id}/read") {
        val node = call.parameters["id"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }?.let(read::node)
        if (node == null) call.respondText("not found", status = HttpStatusCode.NotFound)
        else call.respondHtml { page(node.title ?: "Read", settingsHref, Tab.NONE) { readingView(node) } }
    }
    get("/assets/datastar.js") {
        call.respondText(WebPlatform.datastarRuntime(), ContentType("application", "javascript"))
    }
    get("/assets/zync-gestures.js") {
        call.respondText(WebPlatform.asset("zync-gestures.js"), ContentType("application", "javascript"))
    }
    get("/assets/pico.min.css") {
        call.respondText(WebPlatform.asset("pico.min.css"), ContentType.Text.CSS)
    }
    get("/assets/custom.css") {
        call.respondText(WebPlatform.asset("custom.css"), ContentType.Text.CSS)
    }
    get("/assets/fonts/{file}") {
        // Allowlist the exact vendored names — the param is joined into a resource path.
        val file = call.parameters["file"]?.takeIf { it.matches(Regex("(geomini-var|iosevkacharonmono-[47]00)\\.woff2")) }
        if (file == null) call.respondText("not found", status = HttpStatusCode.NotFound)
        else call.respondBytes(WebPlatform.assetBytes("fonts/$file"), ContentType("font", "woff2"))
    }

    if (changes != null) {
        sse("/updates") {
            // The cookie at stream-open time pins the filter; a context switch is a
            // page navigation, which reopens the stream.
            val context = call.selectedContext()
            suspend fun pushInbox() = patch(
                patchElementsEvent(WebPlatform.renderFragment("inbox") { inboxSection(read, inbox(), now(), context) }),
            )
            pushInbox()
            changes.changes.collect { pushInbox() }
        }
        sse("/updates/next") {
            val context = call.selectedContext()
            suspend fun pushNext() = patch(
                patchElementsEvent(WebPlatform.renderFragment("next") { nextSection(read, inbox(), now(), context) }),
            )
            pushNext()
            changes.changes.collect { pushNext() }
        }
        sse("/updates/projects") {
            suspend fun pushProjects() = patch(
                patchElementsEvent(WebPlatform.renderFragment("projects") { projectsSection(read, now(), inbox()) }),
            )
            pushProjects()
            changes.changes.collect { pushProjects() }
        }
        sse("/updates/reference") {
            suspend fun pushReference() = patch(
                patchElementsEvent(WebPlatform.renderFragment("reference") { referenceSection(read, null) }),
            )
            pushReference()
            changes.changes.collect { pushReference() }
        }
    }

    if (commands != null) {
        suspend fun ApplicationCall.applied(mutate: ContentCommands.() -> Unit) {
            commands.mutate()
            changes?.notifyChanged()
            val context = selectedContext()
            respondDatastar(patchElementsEvent(WebPlatform.renderFragment("inbox") { inboxSection(read, inbox(), now(), context) }))
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
            val id = call.nodeId()
            when {
                id == null || parent == null -> call.respondText("bad request", status = HttpStatusCode.BadRequest)
                read.moveWouldExceedDepth(id, parent) ->
                    call.respondText("move would exceed 4 levels", status = HttpStatusCode.Conflict)
                else -> call.applied { move(id, parent) }
            }
        }
        // --- Inbox triage panel (spec §4): size / rename / split / link-description ---
        post("/node/{id}/size") {
            val size = call.request.queryParameters["size"]
            val id = call.nodeId()
            when {
                id == null -> call.respondText("bad request", status = HttpStatusCode.BadRequest)
                !size.isNullOrEmpty() && size !in dev.njr.zync.core.content.Size.ALL ->
                    call.respondText("size must be S|M|L", status = HttpStatusCode.BadRequest)
                else -> call.applied { setSize(id, size?.ifEmpty { null }) }
            }
        }
        post("/node/{id}/rename") {
            val title = call.request.queryParameters["title"]?.trim().orEmpty()
            val id = call.nodeId()
            if (id != null && title.isNotEmpty()) call.applied { rename(id, title) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        post("/node/{id}/split") {
            val title = call.request.queryParameters["title"]?.trim().orEmpty()
            val id = call.nodeId()
            if (id != null && title.isNotEmpty()) call.applied { split(id, title) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        post("/node/{id}/notes") {
            val notes = call.request.queryParameters["notes"].orEmpty().trim()
            val id = call.nodeId()
            if (id != null) call.applied { setNotes(id, notes) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        // Inbox reorder (GTD triage §3): compute a new fractional rank from the current
        // order, then write it. A no-op at an edge still re-renders (harmless).
        post("/node/{id}/rank") {
            val dir = when (call.request.queryParameters["dir"]) {
                "up" -> Reorder.UP
                "down" -> Reorder.DOWN
                "top" -> Reorder.TOP
                else -> null
            }
            val id = call.nodeId()
            if (id == null || dir == null) {
                call.respondText("bad request", status = HttpStatusCode.BadRequest)
            } else {
                val newRank = read.reorderRank(inbox(), id, dir, now())
                call.applied { if (newRank != null) setRank(id, newRank) }
            }
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

        // --- Organize controls (detail page): tree filing, tags, due date, person ---
        post("/node/{id}/move-detail") {
            val parent = call.request.queryParameters["parent"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }
            val id = call.nodeId()
            when {
                id == null || parent == null -> call.respondText("bad request", status = HttpStatusCode.BadRequest)
                read.moveWouldExceedDepth(id, parent) ->
                    call.respondText("move would exceed 4 levels", status = HttpStatusCode.Conflict)
                else -> call.appliedDetail(id) { move(id, parent) }
            }
        }
        post("/node/{id}/tag") {
            val context = call.request.queryParameters["context"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }
            val on = call.request.queryParameters["on"] == "true"
            val id = call.nodeId()
            if (id != null && context != null) {
                call.appliedDetail(id) { if (on) addTag(id, context) else removeTag(id, context) }
            } else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        post("/node/{id}/due") {
            val raw = call.request.queryParameters["date"].orEmpty().trim()
            val millis = if (raw.isEmpty()) null else DueDates.parse(raw)
            val id = call.nodeId()
            when {
                id == null -> call.respondText("bad request", status = HttpStatusCode.BadRequest)
                raw.isNotEmpty() && millis == null ->
                    call.respondText("invalid date: expected YYYY-MM-DD", status = HttpStatusCode.BadRequest)
                else -> call.appliedDetail(id) { setDueDate(id, millis) }
            }
        }
        post("/node/{id}/file") {
            call.nodeId()?.let { id -> call.appliedDetail(id) { file(id) } }
                ?: call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        // File-location suggestion chips (spec §6): accept (Move) / dismiss. Inbox-scoped.
        post("/node/{id}/accept-file") {
            val target = call.request.queryParameters["target"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }
            val id = call.nodeId()
            when {
                id == null || target == null -> call.respondText("bad request", status = HttpStatusCode.BadRequest)
                read.moveWouldExceedDepth(id, target) ->
                    call.respondText("move would exceed 4 levels", status = HttpStatusCode.Conflict)
                else -> call.applied { acceptFileSuggestion(id, target) }
            }
        }
        post("/node/{id}/dismiss-file") {
            call.nodeId()?.let { id -> call.applied { dismissFileSuggestions(id) } }
                ?: call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        // DONE→Reference proposal (spec §7): accept files under it / reject clears. Detail-scoped.
        post("/node/{id}/file-done") {
            val target = call.request.queryParameters["target"]?.let { runCatching { Ulid.parse(it) }.getOrNull() }
            val id = call.nodeId()
            if (id != null && target != null) call.appliedDetail(id) { acceptProposedFile(id, target) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        post("/node/{id}/file-done-reject") {
            call.nodeId()?.let { id -> call.appliedDetail(id) { rejectProposedFile(id) } }
                ?: call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
        post("/node/{id}/person") {
            val name = call.request.queryParameters["name"]
            val id = call.nodeId()
            if (id != null) call.appliedDetail(id) { setPerson(id, name) }
            else call.respondText("bad request", status = HttpStatusCode.BadRequest)
        }
    }
}
