package dev.njr.zync.web.views

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
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
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul

/**
 * The current-context pill (launcher spec L4): pinned above the list, tap to drop
 * down the other contexts. Selection navigates with `?context=` (the server persists
 * it in a cookie), so the SSE stream reopens with the right filter. Pure
 * details/summary — no JS, CSP-safe.
 */
fun FlowContent.contextBar(read: ContentReadModel, selected: Ulid?) {
    val contexts = read.contexts()
    if (contexts.isEmpty() && selected == null) return
    val current = contexts.firstOrNull { it.id.toString() == selected?.toString() }
    details(classes = "context-pill") {
        summary { +(current?.name ?: "All contexts") }
        ul {
            li { a(href = "/?context=none") { +"All contexts" } }
            contexts.forEach { c ->
                li { a(href = "/?context=${c.id}") { +(c.name ?: "(unnamed context)") } }
            }
        }
    }
}

/**
 * The home list: the inbox, or — with a context selected — the flat next-actions
 * view for that context across the whole tree. Quick-add always captures to the inbox.
 */
fun FlowContent.inboxSection(read: ContentReadModel, inbox: Ulid?, now: Long, context: Ulid? = null) {
    contextBar(read, context)
    h2 { +"Inbox" }
    // Quick add: Datastar binds the input to a signal and posts it.
    div(classes = "quick-add") {
        input(type = InputType.text) {
            attributes["data-bind:title"] = ""
            attributes["placeholder"] = "New task"
        }
        button {
            attributes["data-on:click"] = "@post('/inbox?title=' + encodeURIComponent(\$title))"
            +"Add"
        }
    }
    val items = if (context != null) read.contextTasks(context, now) else read.inbox(inbox, now)
    if (items.isEmpty()) {
        p("muted") { +(if (context != null) "No active tasks in this context." else "Inbox zero.") }
    } else {
        ul { items.forEach { li { nodeRow(it) } } }
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

/** A node's detail: title, kind/status, notes, subtasks (decompose), and comments. */
fun FlowContent.nodeDetail(read: ContentReadModel, node: NodeView) {
    h2 { +(node.title ?: "(untitled)") }
    p("muted") { +"${node.kind ?: "node"} · ${node.status ?: ""}" }
    node.notes?.let { p { +it } }
    a(href = "/node/${node.id}/read") { +"Read" }

    h3 { +"Subtasks" }
    val subs = read.children(node.id)
    if (subs.isNotEmpty()) ul { subs.forEach { li { nodeRow(it) } } }
    quickAdd(bind = "subtask", param = "title", action = "/node/${node.id}/subtask", label = "Add subtask")

    h3 { +"Comments" }
    val comments = read.comments(node.id)
    if (comments.isNotEmpty()) ul { comments.forEach { c -> li { +(c.title ?: "") } } }
    quickAdd(bind = "comment", param = "text", action = "/node/${node.id}/comment", label = "Comment")
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
