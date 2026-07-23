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
import kotlinx.html.dataList
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.textArea
import kotlinx.html.ul

/**
 * The full-page GTD item editor (save-as-you-go). Every control @posts on change and the
 * server re-patches `#node-detail` — there is no Save/Cancel; a Close link returns to the
 * list. The caller wraps this in `div{ id="node-detail" }`, so this renders the inner
 * content only. Datastar v1 COLON syntax (`data-on:click`, `data-bind:name`), CSP-safe.
 */
fun FlowContent.nodeEditView(read: ContentReadModel, node: NodeView) {
    div(classes = "edit-page") {

        // 1. Title — Enter or the Save button renames.
        div(classes = "edit-field") {
            span("edit-label") { icon("pencil"); +" Title" }
            input(type = InputType.text) {
                attributes["data-bind:title"] = ""
                attributes["value"] = node.title ?: ""
                attributes["placeholder"] = node.title ?: "Title"
                attributes["data-on:keydown"] =
                    "if (evt.key==='Enter'){ @post('/node/${node.id}/rename?title=' + encodeURIComponent(\$title)) }"
            }
            button(classes = "btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/rename?title=' + encodeURIComponent(\$title))"
                +"Save"
            }
        }

        // 2. Context — chips; tagged toggle off, untagged toggle on.
        div(classes = "edit-field") {
            span("edit-label") { icon("tag"); +" Context" }
            div(classes = "chips-row") {
                read.contexts().forEach { c ->
                    val on = node.tags.any { it.toString() == c.id.toString() }
                    button(classes = if (on) "btn chip-on" else "btn") {
                        attributes["data-on:click"] =
                            "@post('/node/${node.id}/tag?context=${c.id}&on=${if (on) "false" else "true"}')"
                        +(c.name ?: "(context)")
                    }
                }
            }
        }

        // 3. Due date — native date input; Set applies, Clear removes (only when set).
        div(classes = "edit-field") {
            span("edit-label") { icon("calendar"); +" Due" }
            input(type = InputType.date) {
                attributes["data-bind:due"] = ""
                node.dueDate?.let { attributes["value"] = DueDates.format(it) }
            }
            button(classes = "btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/due?date=' + encodeURIComponent(\$due))"
                +"Set"
            }
            if (node.dueDate != null) {
                button(classes = "btn") {
                    attributes["data-on:click"] = "@post('/node/${node.id}/due?date=')"
                    +"Clear"
                }
            }
        }

        // 4. Size — S/M/L chips (current highlighted) + a clear chip.
        div(classes = "edit-field") {
            span("edit-label") { icon("gauge"); +" Size" }
            div(classes = "chips-row") {
                Size.ALL.forEach { s ->
                    button(classes = if (node.size == s) "btn chip-on" else "btn") {
                        attributes["data-on:click"] = "@post('/node/${node.id}/size?size=$s')"
                        +s
                    }
                }
                button(classes = "btn") {
                    attributes["data-on:click"] = "@post('/node/${node.id}/size?size=')"
                    +"clear"
                }
            }
        }

        // 5. Link — URL input; when set, an anchor opens it.
        div(classes = "edit-field") {
            span("edit-label") { icon("link"); +" Link" }
            input(type = InputType.text) {
                attributes["data-bind:link"] = ""
                node.linkUrl?.let { attributes["value"] = it }
                attributes["placeholder"] = "https://…"
            }
            button(classes = "btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/link?url=' + encodeURIComponent(\$link))"
                +"Save"
            }
            node.linkUrl?.let { url ->
                a(href = url) { icon("link"); +" open" }
            }
        }

        // 6. Description — freeform notes; Save posts the current value.
        div(classes = "edit-field") {
            span("edit-label") { icon("doc"); +" Description" }
            textArea {
                attributes["data-bind:notes"] = ""
                attributes["placeholder"] = node.notes ?: "Notes…"
                +(node.notes ?: "")
            }
            button(classes = "btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/notes?notes=' + encodeURIComponent(\$notes))"
                +"Save"
            }
        }

        // 7. Attachment / OCR / summary — read-only (each shown independently when present).
        val atts = read.attachments(node.id)
        if (atts.isNotEmpty() || node.ocrStatus != null || node.summary != null) {
            div(classes = "edit-field") {
                span("edit-label") { icon("paperclip"); +" Attachment" }
                if (atts.isNotEmpty()) ul {
                    atts.forEach { att -> li { +(att.filename ?: att.type ?: "file") } }
                }
                node.ocrStatus?.let { div(classes = "muted") { +ocrLabel(it) } }
                node.summary?.let { s -> div(classes = "summary") { span("summary-label") { +"Summary" }; p { +s } } }
            }
        }

        // 8. Tags — the model has no separate free-form tags yet; context (field 2) covers
        // all tagging today, so nothing extra is rendered here.

        // 9. Waiting-for — toggled picker with a person input + recent-people datalist.
        div(classes = "edit-field") {
            button(classes = "btn") {
                attributes["data-key"] = "w"
                attributes["data-act"] = "waiting"
                attributes["data-on:click"] = "\$wopen = !\$wopen"
                icon("waiting"); +" Waiting for"
            }
            div(classes = "waiting-picker") {
                attributes["data-show"] = "\$wopen"
                input(type = InputType.text) {
                    attributes["data-bind:waiting"] = ""
                    attributes["placeholder"] = node.person ?: "Who?"
                    attributes["list"] = "waiting-people"
                }
                val people = read.nodes().mapNotNull { it.person }.distinct().take(8)
                dataList {
                    id = "waiting-people"
                    people.forEach { option { value = it } }
                }
                button(classes = "btn") {
                    attributes["data-on:click"] =
                        "@post('/node/${node.id}/waiting?name=' + encodeURIComponent(\$waiting))"
                    +"Set"
                }
            }
        }

        // 10. Subtasks — children as links + a quick-add (Enter or +).
        h3 { +"Subtasks" }
        val subs = read.children(node.id)
        if (subs.isNotEmpty()) {
            ul {
                subs.forEach { child ->
                    li {
                        a(href = "/node/${child.id}") { +(child.title ?: "(untitled)") }
                        child.status?.let { span("status") { +" · $it" } }
                    }
                }
            }
        }
        div(classes = "edit-field") {
            input(type = InputType.text) {
                attributes["data-bind:subtask"] = ""
                attributes["placeholder"] = "Add subtask"
                attributes["data-on:keydown"] =
                    "if(evt.key==='Enter'){ @post('/node/${node.id}/subtask?title=' + encodeURIComponent(\$subtask)); \$subtask='' }"
            }
            button(classes = "btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/subtask?title=' + encodeURIComponent(\$subtask)); \$subtask=''"
                icon("plus")
            }
        }

        // 11. Comments — existing comments + a quick-add (Enter or button; clears after).
        h3 { +"Comments" }
        val comments = read.comments(node.id)
        if (comments.isNotEmpty()) {
            ul { comments.forEach { c -> li { +(c.title ?: "") } } }
        }
        div(classes = "edit-field") {
            input(type = InputType.text) {
                attributes["data-bind:comment"] = ""
                attributes["placeholder"] = "Comment"
                attributes["data-on:keydown"] =
                    "if(evt.key==='Enter'){ @post('/node/${node.id}/comment?text=' + encodeURIComponent(\$comment)); \$comment='' }"
            }
            button(classes = "btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/comment?text=' + encodeURIComponent(\$comment)); \$comment=''"
                icon("comment")
            }
        }

        // Bottom action row: destructive + lifecycle + Close.
        div(classes = "actions") {
            button(classes = "btn") {
                attributes["data-key"] = "#"
                attributes["data-act"] = "delete"
                attributes["data-on:click"] = "@post('/node/${node.id}/trash')"
                icon("trash"); +" Delete"
            }
            button(classes = "btn") {
                attributes["data-key"] = "f"
                attributes["data-act"] = "file"
                attributes["data-on:click"] = "\$fopen = !\$fopen"
                icon("folder"); +" File"
            }
            div(classes = "file-picker") {
                attributes["data-show"] = "\$fopen"
                val targets = (read.projects() + read.reference())
                    .filter { it.id.toString() != node.id.toString() }
                targets.forEach { t ->
                    button(classes = "btn") {
                        attributes["data-on:click"] = "@post('/node/${node.id}/file-to?target=${t.id}')"
                        +(t.title ?: "(untitled)")
                    }
                }
            }
            button(classes = "btn") {
                attributes["data-key"] = "x"
                attributes["data-act"] = "done"
                if (node.status == "DONE") {
                    attributes["data-on:click"] = "@post('/node/${node.id}/reopen')"
                    icon("check"); +" Reopen"
                } else {
                    attributes["data-on:click"] = "@post('/node/${node.id}/complete')"
                    icon("check"); +" Done"
                }
            }
            a(href = "/", classes = "btn") { icon("close"); +"Close" }
        }
    }
}
