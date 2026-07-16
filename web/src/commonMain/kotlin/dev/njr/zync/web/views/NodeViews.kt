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
            ul { items.forEach { li { nodeRow(it) } } }
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

/** A single node as a linked row with its status and inline actions. */
fun FlowContent.nodeRow(node: NodeView) {
    a(href = "/node/${node.id}") { +(node.title ?: "(untitled)") }
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

/** A long-form reading view: title + notes as prose. */
fun FlowContent.readingView(node: NodeView) {
    h2 { +(node.title ?: "(untitled)") }
    node.notes?.split("\n\n")?.forEach { para -> p { +para } }
    a(href = "/node/${node.id}") { +"Back" }
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
