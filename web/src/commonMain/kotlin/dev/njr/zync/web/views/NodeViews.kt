package dev.njr.zync.web.views

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
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
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
            ul { items.forEach { li { nodeRow(it, reorderable = true) } } }
        }
    } else {
        contextBar(read, context)
        val items = read.contextTasks(context, now)
        if (items.isEmpty()) {
            p("muted") { +"No active tasks in this context." }
        } else {
            ul { items.forEach { li { nodeRow(it) } } }
        }
    }
    proposalsSection(read)
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
 * The Next Actions list (GTD "Next"): every live, active, non-deferred task across
 * the whole tree, regardless of project or context — the flat "what can I do now"
 * pool. A person tag renders as a `@waiting` marker so delegated items are visible.
 */
fun FlowContent.nextSection(read: ContentReadModel, now: Long) {
    h2 { +"Next" }
    val items = read.activeTasks(now)
    if (items.isEmpty()) {
        p("muted") { +"No next actions. Clarify the inbox to add some." }
    } else {
        ul { items.forEach { li { nodeRow(it) } } }
    }
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
            li {
                a(href = "/node/${project.id}") { +(project.title ?: "(untitled project)") }
                val open = read.inbox(project.id, now).size
                span("status") { +(if (open == 0) "· done" else "· $open open") }
            }
        }
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
        button(classes = "action reorder") {
            attributes["data-on:click"] = "@post('/node/${node.id}/rank?dir=top')"
            attributes["title"] = "Send to top"
            +"⤒"
        }
        button(classes = "action reorder") {
            attributes["data-on:click"] = "@post('/node/${node.id}/rank?dir=up')"
            attributes["title"] = "Move up"
            +"↑"
        }
        button(classes = "action reorder") {
            attributes["data-on:click"] = "@post('/node/${node.id}/rank?dir=down')"
            attributes["title"] = "Move down"
            +"↓"
        }
    }
    a(href = "/node/${node.id}") { +(node.title ?: "(untitled)") }
    node.person?.let { span("waiting") { +" @$it" } }
    node.status?.let { span("status") { +" · $it" } }
    if (!reorderable) {
        // Inbox rows rely on swipes for complete/delete; every other surface keeps buttons.
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
    a(href = "/node/${node.id}/read") { +"Read" }

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
private fun ocrLabel(status: String): String = when (status) {
    "PENDING", "RUNNING" -> "OCR pending…"
    "DONE" -> "OCR done"
    "FAILED" -> "OCR failed"
    else -> "OCR $status"
}

/** A Datastar-bound text input + submit button that posts the signal as a query param. */
private fun FlowContent.quickAdd(bind: String, param: String, action: String, label: String) {
    input(type = InputType.text) {
        attributes["data-bind:$bind"] = ""
        attributes["placeholder"] = label
    }
    button {
        attributes["data-on:click"] = "@post('$action?$param=' + encodeURIComponent(\$$bind))"
        +label
    }
}
