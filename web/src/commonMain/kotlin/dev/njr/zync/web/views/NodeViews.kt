package dev.njr.zync.web.views

import dev.njr.zync.core.content.Size
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.NodeView
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.summary
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.li
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
            ul { items.forEach { itemLi(read, it, canReorder = true) } }
        }
    } else {
        val items = read.contextTasks(context, now)
        if (items.isEmpty()) {
            p("muted") { +"No active tasks in this context." }
        } else {
            ul { items.forEach { itemLi(read, it) } }
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
                node.kind?.let { span("status") { +" · $it" } }
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
                itemLi(read, row.action, lead = {
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
    else ul { items.forEach { itemLi(read, it) } }
}

/**
 * The Projects list (GTD "Projects"): every live project, each a link into its detail
 * (subtasks, tree, organize controls) with a count of its open direct next-actions.
 */
fun FlowContent.projectsSection(read: ContentReadModel, now: Long, inbox: Ulid? = null) {
    h2 { +"Projects" }
    // The inbox is a project node under the hood; it has its own tab, so keep it out here.
    val projects = read.projects().filter { it.id.toString() != inbox?.toString() }
    if (projects.isEmpty()) {
        p("muted") { +"No projects yet. Convert an inbox item into a project to start one." }
        return
    }
    ul {
        projects.forEach { project ->
            val open = read.inbox(project.id, now).size
            itemLi(read, project, lead = { span("status") { +(if (open == 0) " · done" else " · $open open") } })
        }
    }
}

/**
 * A list item for ANY view: a collapsed title (tap to expand) + the inline [expandedPanel].
 * [canReorder] shows the drag handle (order-stored lists only — inbox + a project's subtasks).
 * [lead] injects inline content right after the title (a Next project label, a Projects count).
 */
fun UL.itemLi(
    read: ContentReadModel,
    node: NodeView,
    canReorder: Boolean = false,
    lead: (FlowContent.() -> Unit)? = null,
) {
    li(classes = "item swipe-row") {
        attributes["data-node"] = node.id.toString()
        attributes["data-complete"] = "/node/${node.id}/complete"
        attributes["data-trash"] = "/node/${node.id}/trash"
        span(classes = "row-title") {
            attributes["data-on:click"] = "\$exp = (\$exp === '${node.id}' ? '' : '${node.id}')"
            +(node.title ?: "(untitled)")
        }
        lead?.invoke(this)
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

        // Drag handle (pointer-based, touch-friendly) — only where sibling order is stored
        // (inbox + a project's subtasks), not the computed Next/Today views.
        if (canReorder) div(classes = "exp-head") {
            span(classes = "drag-handle") {
                attributes["data-drag"] = ""
                attributes["title"] = "Drag to reorder"
                icon("grip")
            }
        }

        // Read-only fields, in order (title is the head above); omitted when unset.
        div(classes = "fields") {
            val ctx = read.contexts().filter { c -> node.tags.any { it.toString() == c.id.toString() } }.mapNotNull { it.name }
            if (ctx.isNotEmpty()) div(classes = "f-row") { icon("tag"); +ctx.joinToString(" ") }
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
                button(classes = "btn") {
                    attributes["data-act"] = "file"; attributes["data-key"] = "f"
                    attributes["data-on:click"] = "\$fo_${node.id} = !\$fo_${node.id}"
                    icon("folder"); +"File"
                }
                div(classes = "file-picker") {
                    attributes["data-show"] = "\$fo_${node.id}"
                    (read.projects() + read.reference()).filter { it.id.toString() != node.id.toString() }.forEach { t ->
                        button(classes = "btn") {
                            attributes["data-on:click"] = "@post('/node/${node.id}/file-to?target=${t.id}')"
                            +(t.title ?: "(untitled)")
                        }
                    }
                }
            }
            div(classes = "snooze-wrap") {
                button(classes = "btn") {
                    attributes["data-act"] = "snooze"; attributes["data-key"] = "s"
                    attributes["data-on:click"] = "\$so_${node.id} = !\$so_${node.id}"
                    icon("clock"); +"Snooze"
                }
                div(classes = "snooze-menu") {
                    attributes["data-show"] = "\$so_${node.id}"
                    snoozeOption(node.id, "Tomorrow", 1)
                    snoozeOption(node.id, "In 2 days", 2)
                    snoozeOption(node.id, "Next week", 7)
                    snoozeOption(node.id, "In 2 weeks", 14)
                }
            }
            a(href = "/node/${node.id}", classes = "btn") {
                attributes["data-act"] = "edit"; attributes["data-key"] = "e"
                icon("pencil"); +"Edit"
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

/** The host of a URL, for the compact link label (falls back to the whole URL). */
private fun linkLabel(url: String): String = url.substringAfter("://").substringBefore("/").ifBlank { url }

/**
 * The Reference surface (GTD triage §7): a keyword search box over ALL content plus
 * the filed tree. Search debounces a GET that patches #reference-results (Datastar v1
 * COLON syntax; no inline script — CSP-safe).
 */
fun FlowContent.referenceSection(read: ContentReadModel, query: String? = null) {
    h2 { +"Reference" }
    input(type = InputType.search) {
        id = "search"
        attributes["data-bind:q"] = ""
        attributes["placeholder"] = "Search everything…"
        attributes["data-on:input__debounce.300ms"] = "@get('/reference/search?q=' + encodeURIComponent(\$q))"
    }
    div {
        id = "reference-results"
        referenceResults(read, query)
    }
}

fun FlowContent.referenceResults(read: ContentReadModel, query: String?) {
    if (!query.isNullOrBlank()) {
        val hits = read.search(query)
        if (hits.isEmpty()) p("muted") { +"No matches." } else ul { hits.forEach { itemLi(read, it) } }
    } else {
        val filed = read.reference()
        if (filed.isEmpty()) p("muted") { +"Nothing filed yet." } else ul { filed.forEach { itemLi(read, it) } }
    }
}

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

/** A node's detail: title, kind/status, notes, organize controls, subtasks, comments. */
fun FlowContent.nodeDetail(read: ContentReadModel, node: NodeView) {
    h2 { +(node.title ?: "(untitled)") }
    p("muted") {
        +"${node.kind ?: "node"} · ${node.status ?: ""}"
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
    // Operator proposal to file a DONE task into Reference (GTD §7, Q5).
    node.proposedFileParent?.let { target ->
        val area = read.node(target)
        div(classes = "file-banner") {
            +"File to ${area?.title ?: "Reference"}? "
            button(classes = "action") {
                attributes["data-on:click"] = "@post('/node/${node.id}/file-done?target=$target')"
                +"Accept"
            }
            button(classes = "action") {
                attributes["data-on:click"] = "@post('/node/${node.id}/file-done-reject')"
                +"No"
            }
        }
    }
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

/** A long-form reading view: title + summary + notes as prose. */
fun FlowContent.readingView(node: NodeView) {
    h2 { +(node.title ?: "(untitled)") }
    node.summary?.let { s ->
        div(classes = "summary") {
            span("summary-label") { +"Summary" }
            p { +s }
        }
    }
    node.notes?.split("\n\n")?.forEach { para -> p { +para } }
    a(href = "/node/${node.id}") { +"Back" }
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
