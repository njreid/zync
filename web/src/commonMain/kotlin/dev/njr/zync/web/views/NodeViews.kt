package dev.njr.zync.web.views

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.NodeView
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul

/** The inbox list — a quick-add form plus live, non-completed, non-deferred children. */
fun FlowContent.inboxSection(read: ContentReadModel, inbox: Ulid?, now: Long) {
    h2 { +"Inbox" }
    // Quick add: Datastar binds the input to a signal and posts it.
    input(type = InputType.text) {
        attributes["data-bind"] = "title"
        attributes["placeholder"] = "New task"
    }
    button {
        attributes["data-on-click"] = "@post('/inbox?title=' + encodeURIComponent(\$title))"
        +"Add"
    }
    val items = read.inbox(inbox, now)
    if (items.isEmpty()) {
        p("muted") { +"Inbox zero." }
    } else {
        ul { items.forEach { li { nodeRow(it) } } }
    }
}

/** A single node as a linked row with its status and inline actions. */
fun FlowContent.nodeRow(node: NodeView) {
    a(href = "/node/${node.id}") { +(node.title ?: "(untitled)") }
    node.status?.let { span("status") { +" · $it" } }
    button(classes = "action") {
        attributes["data-on-click"] = "@post('/node/${node.id}/complete')"
        attributes["title"] = "Complete"
        +"✓"
    }
    button(classes = "action") {
        attributes["data-on-click"] = "@post('/node/${node.id}/trash')"
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

/** A node's detail: title, kind/status, notes, and its subtasks. */
fun FlowContent.nodeDetail(read: ContentReadModel, node: NodeView) {
    h2 { +(node.title ?: "(untitled)") }
    p("muted") { +"${node.kind ?: "node"} · ${node.status ?: ""}" }
    node.notes?.let { p { +it } }
    val subs = read.children(node.id)
    if (subs.isNotEmpty()) {
        h3 { +"Subtasks" }
        ul { subs.forEach { li { nodeRow(it) } } }
    }
}
