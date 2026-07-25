package dev.njr.zync.web.views

import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.ReadingState
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.FileArea
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.NodeType
import dev.njr.zync.web.content.NodeView
import dev.njr.zync.web.content.ProjectState
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.h4
import kotlinx.html.summary
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.pre
import kotlinx.html.progress
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.UL
import kotlinx.html.ul

/**
 * The current-context pill (launcher spec L4), shown only on the context view: tap
 * to switch context or return to the Inbox. Selection navigates with `?context=`
 * (the server persists it in a cookie), so the SSE stream reopens with the right
 * filter. Pure details/summary — no JS, CSP-safe.
 */
fun FlowContent.contextBar(read: ContentReadModel, selected: Ulid) {
    val contexts = read.contexts()
    val current = contexts.firstOrNull { it.id.toString() == selected.toString() }
    details(classes = "context-pill") {
        summary { +(current?.name ?: "(context)") }
        ul {
            li { a(href = "/?context=none") { +"Inbox" } }
            contexts.forEach { c ->
                li { a(href = "/?context=${c.id}") { +(c.name ?: "(unnamed context)") } }
            }
        }
    }
}

/**
 * The home list: the inbox, or — with a context selected — the flat next-actions
 * view for that context across the whole tree. Deliberately NO entry field and NO
 * context filter on the inbox: it is a pure triage surface (clarify, file into the
 * tree, subdivide) — creation belongs to capture, doing belongs to context views.
 */
fun FlowContent.inboxSection(read: ContentReadModel, inbox: Ulid?, now: Long, context: Ulid? = null) {
    if (context == null) {
        h2 { +"Inbox" }
        val items = read.inbox(inbox, now)
        if (items.isEmpty()) {
            p("muted") { +"Inbox zero." }
        } else {
            ul { items.forEach { itemLi(read, it, canReorder = true, now = now) } }
        }
    } else {
        val items = read.contextTasks(context, now)
        if (items.isEmpty()) {
            p("muted") { +"No active tasks in this context." }
        } else {
            ul { items.forEach { itemLi(read, it, now = now) } }
        }
    }
    proposalsSection(read)
    suggestionsSection(read)
}

/**
 * Bot-proposed field edits awaiting review (external-op-api §4). Each shows the diff
 * (field: current → proposed) + who proposed it; accept applies the change as a human op,
 * dismiss drops the suggestion. Rendered in the inbox fragment so SSE keeps it current.
 */
fun FlowContent.suggestionsSection(read: ContentReadModel) {
    val suggestions = read.suggestions()
    if (suggestions.isEmpty()) return
    h2 { +"Suggestions" }
    ul {
        suggestions.forEach { s ->
            li {
                span("proposed") { +(s.targetTitle ?: "(item)") }
                span("status") {
                    val proposed = (s.proposedValue as? kotlinx.serialization.json.JsonPrimitive)?.content ?: s.proposedValue.toString()
                    +" · ${s.field}: ${s.currentValue ?: "—"} → $proposed"
                }
                s.byBot?.let { span("waiting") { +" @$it" } }
                button(classes = "action") {
                    attributes["data-on:click"] = "@post('/suggestion/${s.id}/accept')"
                    +"✔ Accept"
                }
                button(classes = "action") {
                    attributes["data-on:click"] = "@post('/suggestion/${s.id}/reject')"
                    +"✖ Dismiss"
                }
            }
        }
    }
}

/**
 * Agent proposals awaiting human review (spec §8: agent output is never live until
 * accepted). Rendered inside the inbox fragment so SSE updates keep it current.
 * Empty until the M9 agent runtime lands; the accept/reject ops are real today.
 */
fun FlowContent.proposalsSection(read: ContentReadModel) {
    val proposals = read.proposals()
    if (proposals.isEmpty()) return
    h2 { +"Proposals" }
    ul {
        proposals.forEach { node ->
            li {
                span("proposed") { +(node.title ?: "(untitled proposal)") }
                button(classes = "action") {
                    attributes["data-on:click"] = "@post('/proposal/${node.id}/accept')"
                    attributes["title"] = "Accept"
                    +"✔ Accept"
                }
                button(classes = "action") {
                    attributes["data-on:click"] = "@post('/proposal/${node.id}/reject')"
                    attributes["title"] = "Reject"
                    +"✖ Reject"
                }
            }
        }
    }
}

/**
 * The Next Actions surface (spec §5, context-scoped): the top loose root action, then
 * each project's first completable action (one row per project). Context is a manual
 * pick (RESOLVED Q6) via the same ?context= cookie the inbox uses; null = "any".
 */
fun FlowContent.nextSection(read: ContentReadModel, inbox: Ulid?, now: Long, context: Ulid? = null) {
    h2 { +"Next" }
    val rows = read.nextActions(context, inbox, now)
    if (rows.isEmpty()) {
        p("muted") { +"No next actions. Clarify the inbox to add some." }
    } else {
        ul {
            rows.forEach { row ->
                itemLi(read, row.action, now = now, lead = {
                    row.project?.let { proj ->
                        +" · "
                        a(href = "/node/${proj.id}") { span("project") { +(proj.title ?: "(project)") } }
                    }
                })
            }
        }
    }
}

/**
 * The Today surface: tasks due on or before end-of-today (context-filtered when one is picked).
 * Mirrors the native home "due" tile.
 */
fun FlowContent.todaySection(read: ContentReadModel, now: Long, context: Ulid? = null) {
    h2 { +"Today" }
    val byMillis = DueDates.parse(DueDates.format(now)) ?: now // today's date → end-of-today cutoff
    val items = read.dueTasks(byMillis)
        .let { list -> if (context == null) list else list.filter { n -> n.tags.any { it.toString() == context.toString() } } }
    if (items.isEmpty()) p("muted") { +"Nothing due today." }
    else ul { items.forEach { itemLi(read, it, now = now) } }
}

/**
 * The Projects list (GTD "Projects"): every live project, each a link into its detail
 * (subtasks, tree, organize controls) with a count of its open direct next-actions.
 */
fun FlowContent.projectsSection(read: ContentReadModel, now: Long, inbox: Ulid? = null) {
    h2 { +"Projects" }
    val projects = read.projects().filter { it.id.toString() != inbox?.toString() }
    if (projects.isEmpty()) {
        p("muted") { +"No projects yet. Give an inbox item a subtask to start one." }
        return
    }
    ul {
        projects.forEach { project ->
            // Derived project state: STALLED (no next action) is the GTD red flag; else open count.
            val badge: FlowContent.() -> Unit = when (read.projectState(project.id)) {
                ProjectState.STALLED -> { { span("status stalled") { +" · stalled" } } }
                ProjectState.DONE -> { { span("status") { +" · done" } } }
                ProjectState.ACTIVE -> { { span("status") { +" · ${read.inbox(project.id, now).size} open" } } }
            }
            itemLi(read, project, now = now, lead = badge)
        }
    }
}

/**
 * A list item for ANY view: a collapsed title (tap to expand) + the inline [expandedPanel].
 * [canReorder] shows the drag handle (order-stored lists only — inbox + a project's subtasks).
 * [lead] injects inline content right after the title (a Next project label, a Projects count).
 */
/**
 * Row urgency class from status + due date (replaces the old "active" text label):
 * done → dim + strikethrough, overdue → red, due within 2 days → orange, else white.
 * With no clock ([now] = MAX, e.g. the Reference archive) only done vs. active is shown.
 */
internal fun statusClass(node: NodeView, now: Long): String = when {
    node.status == "DONE" || node.status == "DROPPED" -> "st-done"
    node.dueDate == null || now == Long.MAX_VALUE -> "st-active"
    now > node.dueDate -> "st-overdue"
    node.dueDate - now <= 2 * 86_400_000L -> "st-soon"
    else -> "st-active"
}

fun UL.itemLi(
    read: ContentReadModel,
    node: NodeView,
    canReorder: Boolean = false,
    now: Long = Long.MAX_VALUE,
    lead: (FlowContent.() -> Unit)? = null,
) {
    li(classes = "item swipe-row") {
        attributes["data-node"] = node.id.toString()
        attributes["data-complete"] = "/node/${node.id}/complete"
        attributes["data-trash"] = "/node/${node.id}/trash"
        // Header row: drag handle (left of the title, only when expanded + reorderable) and the
        // title in a flex row so a long title wraps beside the handle, not onto its own line.
        div(classes = "item-head") {
            if (canReorder) span(classes = "drag-handle") {
                attributes["data-show"] = "\$exp === '${node.id}'"
                attributes["data-drag"] = ""
                attributes["title"] = "Drag to reorder"
                icon("grip")
            }
            span(classes = "row-title ${statusClass(node, now)}") {
                attributes["data-on:click"] = "\$exp = (\$exp === '${node.id}' ? '' : '${node.id}')"
                attributes["data-attr:data-expanded"] = "\$exp === '${node.id}' ? 'true' : 'false'"
                +(node.title ?: "(untitled)")
            }
            lead?.invoke(this)
        }
        button(classes = "swipe-fire complete") {
            attributes["data-on:click"] = "@post('/node/${node.id}/complete')"; attributes["aria-label"] = "Complete"; +"Complete"
        }
        button(classes = "swipe-fire trash") {
            attributes["data-on:click"] = "@post('/node/${node.id}/trash')"; attributes["aria-label"] = "Delete"; +"Delete"
        }
        button(classes = "undo") { attributes["data-undo"] = ""; +"Undo" }
        expandedPanel(read, node, canReorder)
    }
}

/**
 * A single node as a linked row with its status and inline actions. In the inbox
 * ([reorderable]) it carries send-to-top / move-up / move-down controls that rewrite
 * the node's fractional `rank` (GTD triage §3, spec Q2 = buttons for v1), and it
 * drops the complete/trash buttons — swipe-right completes, swipe-left deletes
 * (spec §4). Other surfaces keep the explicit complete/trash buttons.
 */
fun FlowContent.nodeRow(node: NodeView, reorderable: Boolean = false) {
    if (reorderable) {
        // Collapsed = title only; tap to expand the panel (details + actions live there).
        span(classes = "row-title") {
            attributes["data-on:click"] = "\$exp = (\$exp === '${node.id}' ? '' : '${node.id}')"
            +(node.title ?: "(untitled)")
        }
        // Visually-hidden but FOCUSABLE + labeled Datastar triggers: the gesture helper .click()s
        // them on swipe/keypress (swipe-right/x = complete, swipe-left/# = trash).
        button(classes = "swipe-fire complete") {
            attributes["data-on:click"] = "@post('/node/${node.id}/complete')"
            attributes["aria-label"] = "Complete"
            +"Complete"
        }
        button(classes = "swipe-fire trash") {
            attributes["data-on:click"] = "@post('/node/${node.id}/trash')"
            attributes["aria-label"] = "Delete"
            +"Delete"
        }
        // Shown only during a swipe's 3s undo window (CSS: `.pending .undo`).
        button(classes = "undo") {
            attributes["data-undo"] = ""
            +"Undo"
        }
    } else {
        a(href = "/node/${node.id}") { +(node.title ?: "(untitled)") }
        node.person?.let { span("waiting") { +" @$it" } }
        node.size?.let { span("size-badge") { +it } }
        node.status?.let { span("status") { +" · $it" } }
        button(classes = "action") {
            attributes["data-on:click"] = "@post('/node/${node.id}/complete')"
            attributes["title"] = "Complete"
            +"✓"
        }
        button(classes = "action") {
            attributes["data-on:click"] = "@post('/node/${node.id}/trash')"
            attributes["title"] = "Trash"
            +"🗑"
        }
    }
}

/**
 * The Expanded panel: read-only fields + subtasks + a bottom action row (File · Snooze · Edit),
 * with a drag handle top-left for reordering. Open state is the Datastar `$exp` signal (holds the
 * expanded node id) so it survives the SSE morph and only one is open at a time. Fields render in
 * the agreed order and a field is OMITTED when unset. CSP-safe (no inline script/style).
 */
private fun FlowContent.expandedPanel(read: ContentReadModel, node: NodeView, canReorder: Boolean) {
    div(classes = "expanded") {
        attributes["data-show"] = "\$exp === '${node.id}'"

        // (The drag handle now sits to the left of the title in the row, not here.)
        // Read-only fields, in order (title is the row above); omitted when unset.
        div(classes = "fields") {
            val ctx = read.contexts().filter { c -> node.tags.any { it.toString() == c.id.toString() } }.mapNotNull { it.name }
            if (ctx.isNotEmpty()) div(classes = "f-row") { icon("tag"); span("ctx") { +ctx.joinToString(" ") } }
            node.dueDate?.let { d -> div(classes = "f-row") { icon("calendar"); +DueDates.format(d) } }
            node.size?.let { s -> div(classes = "f-row") { icon("gauge"); +s } }
            node.linkUrl?.let { url -> div(classes = "f-row") { icon("link"); a(href = url) { +linkLabel(url) } } }
            node.notes?.let { n -> div(classes = "f-row") { +n } }
            val atts = read.attachments(node.id)
            if (atts.isNotEmpty()) div(classes = "f-row") {
                icon("paperclip")
                +atts.joinToString(", ") { it.filename ?: it.type ?: "file" }
                node.ocrStatus?.let { +" · ${ocrLabel(it)}" }
            }
            node.summary?.let { s -> div(classes = "f-row muted") { +s } }
            if (node.freeTags.isNotEmpty()) div(classes = "f-row") { icon("tag"); +node.freeTags.joinToString(" ") { "#$it" } }
            node.person?.let { p -> div(classes = "f-row") { icon("waiting"); +"@$p" } }
        }

        // Subtasks (nested to the model's 4 levels; tap one to open it).
        if (read.children(node.id).isNotEmpty()) subtaskTree(read, node.id, levelsLeft = 3)

        // Operator file suggestions (if any) remain available as quick chips.
        if (node.fileSuggestions.isNotEmpty()) div(classes = "f-row chips-row") {
            node.fileSuggestions.forEach { sug ->
                button(classes = "btn") {
                    attributes["data-on:click"] = "@post('/node/${node.id}/accept-file?target=${sug.targetId}')"
                    icon("folder"); +sug.title
                }
            }
        }

        // Action row: File · Snooze · Edit.
        div(classes = "actions") {
            div(classes = "snooze-wrap") {
                button(classes = "btn icon-btn") {
                    attributes["data-act"] = "file"; attributes["data-key"] = "f"; attributes["title"] = "File"
                    attributes["aria-label"] = "File"
                    attributes["data-on:click"] = "\$fo_${node.id} = !\$fo_${node.id}; \$so_${node.id} = false"
                    icon("folder")
                }
                div(classes = "file-picker") {
                    attributes["data-show"] = "\$fo_${node.id}"
                    // Both roots are destinations: Projects → top level (ROOT sentinel un-parents),
                    // Reference → the reference root node.
                    fileSection(read, node, FileArea.PROJECTS, "Projects", WellKnownNodes.PROJECTS_ROOT)
                    fileSection(read, node, FileArea.REFERENCE, "Reference", WellKnownNodes.REFERENCE_ROOT)
                }
            }
            div(classes = "snooze-wrap") {
                button(classes = "btn icon-btn") {
                    attributes["data-act"] = "snooze"; attributes["data-key"] = "s"; attributes["title"] = "Snooze"
                    attributes["aria-label"] = "Snooze"
                    attributes["data-on:click"] = "\$so_${node.id} = !\$so_${node.id}; \$fo_${node.id} = false"
                    icon("clock")
                }
                div(classes = "snooze-menu") {
                    attributes["data-show"] = "\$so_${node.id}"
                    snoozeOption(node.id, "Tomorrow", 1)
                    snoozeOption(node.id, "In 2 days", 2)
                    snoozeOption(node.id, "Next week", 7)
                    snoozeOption(node.id, "In 2 weeks", 14)
                }
            }
            a(href = "/node/${node.id}", classes = "btn icon-btn btn-primary") {
                attributes["data-act"] = "edit"; attributes["data-key"] = "e"; attributes["title"] = "Edit"
                attributes["aria-label"] = "Edit"
                icon("pencil")
            }
        }
    }
}

/** A snooze preset: defer the node by [days], computed client-side (relative to now, not render). */
private fun FlowContent.snoozeOption(id: Ulid, label: String, days: Int) {
    button(classes = "btn") {
        attributes["data-on:click"] = "@post('/node/$id/defer?until=' + (Date.now() + $days*86400000))"
        +label
    }
}

/**
 * One filing area in the File picker: the ranked, path-labeled destinations (top few), plus a
 * "… more" button that opens a full-tree dialog with a filter box. Contexts/roots render mono.
 * The area root (Reference only — Projects has no single root node) is itself a destination.
 * Signals: `$dlg_<id>` holds the open dialog's area name; `$dq_<id>` is that dialog's filter.
 */
internal fun FlowContent.fileSection(
    read: ContentReadModel,
    node: NodeView,
    area: FileArea,
    label: String,
    root: Ulid?,
) {
    val candidates = read.fileCandidates(area, node.id)
    div(classes = "fp-section") {
        span("fp-head ctx") { +label }
        root?.let { r ->
            button(classes = "btn") {
                attributes["data-on:click"] = "@post('/node/${node.id}/file-to?target=$r')"
                icon("folder"); +" $label"
            }
        }
        candidates.take(6).forEach { c ->
            button(classes = "btn") {
                attributes["data-on:click"] = "@post('/node/${node.id}/file-to?target=${c.id}')"
                +c.path.ifBlank { label }
            }
        }
        // Always offer the full tree — the ranked shortlist rarely holds everything.
        button(classes = "btn fp-more") {
            attributes["data-on:click"] = "\$dlg_${node.id} = '${area.name}'"
            attributes["title"] = "Browse all of $label"
            +"…"
        }
    }
    // Full-tree dialog for this area: a filter box + every destination (client-side filtered).
    div(classes = "tree-dialog") {
        attributes["data-show"] = "\$dlg_${node.id} === '${area.name}'"
        div(classes = "tree-dialog-card") {
            div(classes = "tree-dialog-head") {
                input(type = InputType.search) {
                    attributes["data-bind:dq_${node.id}"] = ""
                    attributes["placeholder"] = "Filter $label…"
                }
                button(classes = "btn") {
                    attributes["data-on:click"] = "\$dlg_${node.id} = ''"
                    icon("close")
                }
            }
            div(classes = "tree-dialog-list") {
                root?.let { r ->
                    button(classes = "btn") {
                        attributes["data-on:click"] = "@post('/node/${node.id}/file-to?target=$r')"
                        +label
                    }
                }
                candidates.forEach { c ->
                    val needle = c.path.lowercase().replace(Regex("['\\\\]"), "")
                    button(classes = "btn") {
                        attributes["data-show"] =
                            "\$dq_${node.id} === '' || '$needle'.includes((\$dq_${node.id}).toLowerCase())"
                        attributes["data-on:click"] = "@post('/node/${node.id}/file-to?target=${c.id}')"
                        +c.path.ifBlank { label }
                    }
                }
            }
        }
    }
}

/** The host of a URL, for the compact link label (falls back to the whole URL). */
private fun linkLabel(url: String): String = url.substringAfter("://").substringBefore("/").ifBlank { url }

/**
 * The Reference surface (GTD triage §7): a keyword search box over ALL content plus
 * the filed tree. Search debounces a GET that patches #reference-results (Datastar v1
 * COLON syntax; no inline script — CSP-safe).
 */
fun FlowContent.referenceSection(read: ContentReadModel, folder: Ulid? = null, query: String? = null) {
    h2 { +"Reference" }
    input(type = InputType.search) {
        id = "search"
        attributes["data-bind:q"] = ""
        attributes["placeholder"] = "Search everything…"
        attributes["data-on:input__debounce.300ms"] = "@get('/reference/search?q=' + encodeURIComponent(\$q))"
    }
    div {
        id = "reference-results"
        referenceResults(read, folder, query)
    }
    referenceContextMenu(read)
}

fun FlowContent.referenceResults(read: ContentReadModel, folder: Ulid?, query: String?) {
    if (!query.isNullOrBlank()) {
        val hits = read.search(query)
        if (hits.isEmpty()) p("muted") { +"No matches." }
        else ul(classes = "ref-list") { hits.forEach { li(classes = "ref-row") { referenceHitRow(read, it) } } }
        return
    }
    readLaterQueue(read)
    referenceBreadcrumb(read, folder)
    val here = folder ?: WellKnownNodes.REFERENCE_ROOT
    // A place to start a top-level folder (nested folders/items come from a folder's long-press menu).
    if (folder == null) div(classes = "ref-root-add") {
        attributes["data-signals"] = "{nf: ''}"
        input(type = InputType.text) { attributes["data-bind:nf"] = ""; attributes["placeholder"] = "New folder…" }
        button {
            attributes["data-on:click"] =
                "@post('/reference/${WellKnownNodes.REFERENCE_ROOT}/add-folder?title=' + encodeURIComponent(\$nf)).then(() => location.reload())"
            +"Add folder"
        }
    }
    val kids = read.referenceChildren(here)
    if (kids.isEmpty()) p("muted") { +(if (folder == null) "Nothing filed yet." else "Empty folder.") }
    else ul(classes = "ref-list") { kids.forEach { li(classes = "ref-row") { referenceRow(read, it) } } }
}

/** Reference breadcrumb: Reference / folder / … up to (but not including) the current folder's name. */
private fun FlowContent.referenceBreadcrumb(read: ContentReadModel, folder: Ulid?) {
    div(classes = "ref-crumb") {
        a(href = "/reference") { +"Reference" }
        if (folder != null) {
            read.referenceAncestors(folder).forEach { f -> +" / "; a(href = "/reference/${f.id}") { +(f.title ?: "(folder)") } }
            read.node(folder)?.let { +" / "; span { +(it.title ?: "(folder)") } }
        }
    }
}

/** One name-only reference row: a folder (drills in) or an item (opens its preview). data-ref-id
 *  carries the id + type for the long-press context menu. */
private fun FlowContent.referenceRow(read: ContentReadModel, node: NodeView) {
    val folder = read.typeOf(node.id) == NodeType.REFERENCE_FOLDER
    a(href = "/reference/${node.id}", classes = "ref-link") {
        attributes["data-ref-id"] = node.id.toString()
        attributes["data-ref-kind"] = if (folder) "folder" else "item"
        icon(if (folder) "folder" else "doc")
        +" ${node.title ?: "(untitled)"}"
    }
    // Hidden Datastar trigger the long-press handler .click()s to open the context menu (CSP-safe,
    // mirrors the swipe-fire pattern).
    button(classes = "ref-ctx-fire") {
        attributes["data-ctx-fire"] = node.id.toString()
        attributes["data-on:click"] = "\$rctx = '${node.id}'; \$rkind = '${if (folder) "folder" else "item"}'"
        +"menu"
    }
}

/** The reference context menu (one per page): actions target whichever row long-press opened it. */
private fun FlowContent.referenceContextMenu(read: ContentReadModel) {
    fun reload(post: String) = "@post('$post').then(() => location.reload())"
    div(classes = "ref-ctx") {
        attributes["data-signals"] = "{rctx: '', rkind: '', rname: '', cname: '', mto: ''}"
        attributes["data-show"] = "\$rctx !== ''"
        input(type = InputType.text) { attributes["data-bind:rname"] = ""; attributes["placeholder"] = "Rename to…" }
        button { attributes["data-on:click"] = reload("/node/' + \$rctx + '/rename?title=' + encodeURIComponent(\$rname) + '"); +"Rename" }
        div {
            attributes["data-show"] = "\$rkind === 'folder'"
            input(type = InputType.text) { attributes["data-bind:cname"] = ""; attributes["placeholder"] = "New child name…" }
            button { attributes["data-on:click"] = reload("/reference/' + \$rctx + '/add-folder?title=' + encodeURIComponent(\$cname) + '"); +"Add folder" }
            button { attributes["data-on:click"] = reload("/reference/' + \$rctx + '/add-item?title=' + encodeURIComponent(\$cname) + '"); +"Add item" }
        }
        select {
            attributes["data-bind:mto"] = ""
            option { value = ""; +"Move to folder…" }
            read.referenceFolders().forEach { f -> option { value = f.id.toString(); +f.path.ifBlank { "(root)" } } }
        }
        button { attributes["data-on:click"] = reload("/reference/' + \$rctx + '/move?to=' + \$mto + '"); +"Move" }
        button(classes = "danger") { attributes["data-on:click"] = reload("/reference/' + \$rctx + '/delete"); +"Delete" }
        button { attributes["data-on:click"] = "\$rctx = ''"; +"Close" }
    }
}

/** A search hit: title (linked to where it lives) + a "in a / b" breadcrumb for reference hits. */
private fun FlowContent.referenceHitRow(read: ContentReadModel, node: NodeView) {
    val isRef = read.locationOf(node.id) == dev.njr.zync.web.content.Location.REFERENCE
    a(href = if (isRef) "/reference/${node.id}" else "/node/${node.id}", classes = "ref-link") { +(node.title ?: "(untitled)") }
    if (isRef) {
        val crumb = read.referenceCrumb(node.id)
        if (crumb.isNotBlank()) div(classes = "ref-crumb-mini") { +"in $crumb" }
    }
}

/** A reference item's default view: breadcrumb, title, tags, then the markdown body with media
 *  inline (attachment-by-filename images, "/"-path reference links) + a fallback media list. */
fun FlowContent.referenceItemView(read: ContentReadModel, node: NodeView) {
    div(classes = "ref-crumb") {
        a(href = "/reference") { +"Reference" }
        read.referenceAncestors(node.id).forEach { f -> +" / "; a(href = "/reference/${f.id}") { +(f.title ?: "(folder)") } }
    }
    h2 { +(node.title ?: "(untitled)") }
    if (node.freeTags.isNotEmpty()) div(classes = "f-row") { icon("tag"); +node.freeTags.joinToString(" ") { "#$it" } }
    article(classes = "ref-body") {
        renderReaderMarkdown(
            node.notes ?: "",
            image = { name -> read.attachmentByName(node.id, name)?.blobHash?.let { "/blob/$it" } },
            link = { path -> read.referenceNodeByPath(path)?.let { "/reference/$it" } },
        )
    }
    val atts = read.attachments(node.id)
    if (atts.isNotEmpty()) div(classes = "ref-media") {
        atts.forEach { att -> att.blobHash?.let { h -> a(href = "/blob/$h") { +(att.filename ?: att.type ?: "file") } } }
    }
}

/** A reading-first view over Newz exports, independent of where the Read Later project lives. */
private fun FlowContent.readLaterQueue(read: ContentReadModel) {
    val articles = read.savedArticles()
    if (articles.isEmpty()) return
    h3 { +"Read Later" }
    listOf(ReadingState.READING, ReadingState.UNREAD, ReadingState.FINISHED).forEach { state ->
        val matching = articles.filter { it.readingState == state }
        if (matching.isEmpty()) return@forEach
        h4 { +readingStateLabel(state) }
        ul(classes = "reading-queue") {
            matching.forEach { article ->
                val document = readingDocument(article.notes.orEmpty())
                li {
                    a(href = "/node/${article.id}/read") { +(article.title ?: "(untitled)") }
                    span("reading-meta") {
                        document.source?.let { +it }
                        +" · ${readingMinutes(document.markdown)} min · ${article.readingProgress}%"
                    }
                }
            }
        }
    }
}

private fun readingMinutes(markdown: String): Int =
    ((markdown.split(Regex("\\s+")).count { it.isNotBlank() } + 199) / 200).coerceAtLeast(1)

/**
 * The descendant task tree under [parent], one row per line and indented per level, capped at
 * [levelsLeft] more levels (the data model allows 4 levels total, so an inbox item passes 3).
 */
private fun FlowContent.subtaskTree(read: ContentReadModel, parent: Ulid, levelsLeft: Int) {
    if (levelsLeft <= 0) return
    val children = read.children(parent)
    if (children.isEmpty()) return
    ul(classes = "subtasks-list") {
        children.forEach { child ->
            li {
                a(href = "/node/${child.id}") { +(child.title ?: "(untitled)") }
                child.size?.let { span("size-badge") { +it } }
                child.status?.let { span("status") { +" · $it" } }
                subtaskTree(read, child.id, levelsLeft - 1)
            }
        }
    }
}

/** The tree under [parent] (null = root), rendered recursively. */
fun FlowContent.treeSection(read: ContentReadModel, parent: Ulid?) {
    val children = read.children(parent)
    if (children.isEmpty()) return
    ul {
        children.forEach { child ->
            li {
                nodeRow(child)
                treeSection(read, child.id)
            }
        }
    }
}

/** Friendly derived-type word for the detail header. */
internal fun typeLabel(t: NodeType): String = when (t) {
    NodeType.INBOX_ITEM -> "item"
    NodeType.TASK -> "task"
    NodeType.PROJECT -> "project"
    NodeType.REFERENCE_ITEM, NodeType.REFERENCE_FOLDER -> "reference"
    NodeType.ARCHIVED -> "archive"
}

/** A node's detail: title, type/status, notes, organize controls, subtasks, comments. */
fun FlowContent.nodeDetail(read: ContentReadModel, node: NodeView) {
    h2 { +(node.title ?: "(untitled)") }
    p("muted") {
        +"${typeLabel(read.typeOf(node.id))} · ${node.status ?: ""}"
        node.dueDate?.let { +" · due ${DueDates.format(it)}" }
        node.person?.let { +" · @$it" }
        node.ocrStatus?.let { +" · ${ocrLabel(it)}" }
    }
    // Operator-written summary of a scanned document's OCR text (labeled as such).
    node.summary?.let { s ->
        div(classes = "summary") {
            span("summary-label") { +"Summary" }
            p { +s }
        }
    }
    node.notes?.let { p { +it } }
    a(href = "/node/${node.id}/read") { +"Read" }
    // File into Reference (GTD triage §7): archive + move under the reference root.
    if (node.status != "FILED") {
        button(classes = "action") {
            attributes["data-on:click"] = "@post('/node/${node.id}/file')"
            attributes["title"] = "File to Reference"
            +"File"
        }
    }

    organizeSection(read, node)

    h3 { +"Subtasks" }
    val subs = read.children(node.id)
    if (subs.isNotEmpty()) ul { subs.forEach { li { nodeRow(it) } } }
    quickAdd(bind = "subtask", param = "title", action = "/node/${node.id}/subtask", label = "Add subtask")

    h3 { +"Comments" }
    val comments = read.comments(node.id)
    if (comments.isNotEmpty()) ul { comments.forEach { c -> li { +(c.title ?: "") } } }
    quickAdd(bind = "comment", param = "text", action = "/node/${node.id}/comment", label = "Comment")
}

/**
 * Organize controls (the previously missing GTD surface): file into the tree,
 * tag with contexts, set/clear a due date, name a person. All patch #node-detail.
 */
private fun FlowContent.organizeSection(read: ContentReadModel, node: NodeView) {
    h3 { +"Organize" }
    div(classes = "organize") {
        // Move into the tree (projects are the targets; conversion buttons cover the rest).
        val targets = read.projects().filter { it.id.toString() != node.id.toString() }
        if (targets.isNotEmpty()) {
            div(classes = "org-row") {
                select {
                    attributes["data-bind:dest"] = ""
                    option { value = ""; +"Move to project…" }
                    targets.forEach { p -> option { value = p.id.toString(); +(p.title ?: "(untitled)") } }
                }
                button(classes = "action") {
                    attributes["data-on:click"] = "\$dest && @post('/node/${node.id}/move-detail?parent=' + \$dest)"
                    +"Move"
                }
            }
        }

        // Context tags: current as removable chips + the rest addable.
        val contexts = read.contexts()
        if (contexts.isNotEmpty()) {
            div(classes = "org-row chips-row") {
                val tagged = contexts.filter { c -> node.tags.any { it.toString() == c.id.toString() } }
                tagged.forEach { c ->
                    button(classes = "action chip-on") {
                        attributes["data-on:click"] = "@post('/node/${node.id}/tag?context=${c.id}&on=false')"
                        +"${c.name ?: "(context)"} ✕"
                    }
                }
                contexts.filter { it !in tagged }.forEach { c ->
                    button(classes = "action") {
                        attributes["data-on:click"] = "@post('/node/${node.id}/tag?context=${c.id}&on=true')"
                        +"+ ${c.name ?: "(context)"}"
                    }
                }
            }
        }

        // Due date: native date input; empty submit clears.
        div(classes = "org-row") {
            input(type = InputType.date) {
                attributes["data-bind:due"] = ""
                node.dueDate?.let { attributes["value"] = DueDates.format(it) }
            }
            button(classes = "action") {
                attributes["data-on:click"] = "@post('/node/${node.id}/due?date=' + encodeURIComponent(\$due))"
                +"Set due"
            }
            if (node.dueDate != null) {
                button(classes = "action") {
                    attributes["data-on:click"] = "@post('/node/${node.id}/due?date=')"
                    +"Clear"
                }
            }
        }

        // Person (display name; blank clears).
        div(classes = "org-row") {
            input(type = InputType.text) {
                attributes["data-bind:person"] = ""
                attributes["placeholder"] = node.person ?: "Person"
            }
            button(classes = "action") {
                attributes["data-on:click"] = "@post('/node/${node.id}/person?name=' + encodeURIComponent(\$person))"
                +"Set person"
            }
        }
    }
}

/** A long-form reading view for saved articles as well as ordinary Reference notes. */
fun FlowContent.readingView(node: NodeView, canChangeReadingState: Boolean) {
    val document = readingDocument(node.notes.orEmpty())
    article(classes = "reading-view") {
        h2 { +(node.title ?: "(untitled)") }
        document.source?.let { source -> p("muted") { +source } }
        document.originalUrl?.let { url ->
            p("reader-source") { a(href = url) { +"Open original" } }
        }
        if (document.originalUrl != null) {
            div(classes = "reading-actions") {
                span("reading-state") { +readingStateLabel(node.readingState) }
                if (canChangeReadingState) {
                    readingStateAction(node, ReadingState.UNREAD, "Unread")
                    readingStateAction(node, ReadingState.READING, "Reading")
                    readingStateAction(node, ReadingState.FINISHED, "Finished")
                }
            }
            div(classes = "reading-progress") {
                progress {
                    attributes["value"] = node.readingProgress.toString()
                    attributes["max"] = "100"
                }
                span("reading-meta") { +"${node.readingProgress}% read" }
                if (canChangeReadingState) readingProgressControl(node)
            }
        }
        node.summary?.let { s ->
            div(classes = "summary") {
                span("summary-label") { +"Summary" }
                p { +s }
            }
        }
        renderReaderMarkdown(document.markdown)
    }
    a(href = "/node/${node.id}") { +"Back" }
}

private fun FlowContent.readingProgressControl(node: NodeView) {
    div(classes = "reading-progress-control") {
        attributes["data-signals"] = "{ readerProgress: ${node.readingProgress} }"
        input(type = InputType.range) {
            attributes["min"] = "0"
            attributes["max"] = "100"
            attributes["step"] = "1"
            attributes["data-bind:readerProgress"] = ""
        }
        button(classes = "action") {
            attributes["data-on:click"] = "@post('/node/${node.id}/reading-progress?percent=' + \$readerProgress)"
            +"Save position"
        }
    }
}

private fun FlowContent.readingStateAction(node: NodeView, state: String, label: String) {
    button(classes = "action") {
        attributes["data-on:click"] = "@post('/node/${node.id}/reading-state?state=$state')"
        attributes["aria-pressed"] = (node.readingState == state).toString()
        +label
    }
}

private fun readingStateLabel(state: String): String = when (state) {
    ReadingState.READING -> "Reading"
    ReadingState.FINISHED -> "Finished"
    else -> "Unread"
}

private data class ReadingDocument(val source: String?, val originalUrl: String?, val markdown: String)

/** Newz exports two small metadata lines before its Markdown. Keep ordinary notes untouched. */
private fun readingDocument(notes: String): ReadingDocument {
    val lines = notes.lines()
    var index = 0
    val source = lines.getOrNull(index)?.removePrefix("Source: ")?.takeIf { lines.getOrNull(index)?.startsWith("Source: ") == true }
    if (source != null) index++
    val original = lines.getOrNull(index)?.removePrefix("Original: ")?.takeIf { lines.getOrNull(index)?.startsWith("Original: ") == true }
    if (original != null) index++
    while (index < lines.size && lines[index].isBlank()) index++
    return ReadingDocument(source, original?.takeIf(::safeExternalUrl), lines.drop(index).joinToString("\n"))
}

private fun safeExternalUrl(value: String): Boolean = value.startsWith("https://")

/**
 * Conservative Markdown renderer: all text is emitted through kotlinx.html and raw HTML stays text.
 * Optional [image]/[link] resolvers wire the reference-item rules — a relative image name resolves
 * to that item's attachment, a "/"-rooted link to a reference-tree path (both return null = render
 * the literal). Defaults resolve nothing (external https only), so the Newz reader is unchanged.
 */
private fun FlowContent.renderReaderMarkdown(
    markdown: String,
    image: (String) -> String? = { null },
    link: (String) -> String? = { null },
) {
    val lines = markdown.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.isBlank() -> index++
            line.startsWith("```") -> {
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].startsWith("```")) codeLines += lines[index++]
                if (index < lines.size) index++
                pre { code { +codeLines.joinToString("\n") } }
            }
            line.startsWith("### ") -> { h4 { renderInlineMarkdown(line.removePrefix("### "), image, link) }; index++ }
            line.startsWith("## ") -> { h3 { renderInlineMarkdown(line.removePrefix("## "), image, link) }; index++ }
            line.startsWith("# ") -> { h3 { renderInlineMarkdown(line.removePrefix("# "), image, link) }; index++ }
            line.startsWith("- ") || line.startsWith("* ") -> {
                ul {
                    while (index < lines.size && (lines[index].startsWith("- ") || lines[index].startsWith("* "))) {
                        li { renderInlineMarkdown(lines[index].drop(2), image, link) }
                        index++
                    }
                }
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size && lines[index].isNotBlank() && !lines[index].startsWith("#") && !lines[index].startsWith("- ") && !lines[index].startsWith("* ") && !lines[index].startsWith("```")) paragraph += lines[index++]
                p { renderInlineMarkdown(paragraph.joinToString(" "), image, link) }
            }
        }
    }
}

// Matches both a link [label](href) and an image ![alt](src) (the leading "!" is captured).
private val markdownLink = Regex("(!?)\\[([^]]*)]\\(([^)]+)\\)")

private fun FlowContent.renderInlineMarkdown(
    text: String,
    image: (String) -> String? = { null },
    link: (String) -> String? = { null },
) {
    var position = 0
    markdownLink.findAll(text).forEach { match ->
        +text.substring(position, match.range.first)
        val isImage = match.groupValues[1] == "!"
        val label = match.groupValues[2]
        val target = match.groupValues[3]
        if (isImage) {
            val src = when {
                target.startsWith("https://") -> target
                !target.startsWith("/") && !target.contains("://") -> image(target) // relative → attachment
                else -> null
            }
            if (src != null) img(alt = label, src = src) else +match.value
        } else {
            val href = when {
                safeExternalUrl(target) -> target
                target.startsWith("/") -> link(target) // reference-tree path
                else -> null
            }
            if (href != null) a(href = href) { +label } else +match.value
        }
        position = match.range.last + 1
    }
    +text.substring(position)
}

/** Human-readable OCR lifecycle label for the detail meta line. */
internal fun ocrLabel(status: String): String = when (status) {
    "PENDING", "RUNNING" -> "OCR pending…"
    "DONE" -> "OCR done"
    "FAILED" -> "OCR failed"
    else -> "OCR $status"
}

/**
 * A Datastar-bound text input + submit button that posts the signal as a query param. Enter in
 * the field submits and clears it (so another subtask can be typed straight after); the clear is
 * an optimistic `$bind = ''` after the post fires.
 */
private fun FlowContent.quickAdd(bind: String, param: String, action: String, label: String) {
    val submit = "@post('$action?$param=' + encodeURIComponent(\$$bind)); \$$bind = ''"
    input(type = InputType.text) {
        attributes["data-bind:$bind"] = ""
        attributes["placeholder"] = label
        attributes["data-on:keydown"] = "if (evt.key === 'Enter') { $submit }"
    }
    button {
        attributes["data-on:click"] = submit
        +label
    }
}
