package dev.njr.zync.web.views

import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.FileArea
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
import kotlinx.serialization.json.JsonPrimitive

/** JSON-encode a string for a Datastar `data-signals` object literal (proper JS escaping). */
private fun js(s: String?): String = JsonPrimitive(s ?: "").toString()

/**
 * The full-page GTD item editor. TEXT fields (title, due, link, description, waiting-for person)
 * are TRANSACTIONAL: they buffer into Datastar signals, show a green border while changed, and
 * commit together on **Save** (Cancel discards, both leave the page). Chips (context, size, free
 * tags), subtasks, and comments are IMMEDIATE one-tap ops. Datastar v1 colon syntax; CSP-safe.
 * The caller wraps this in `div{ id="node-detail" }`.
 */
fun FlowContent.nodeEditView(read: ContentReadModel, node: NodeView) {
    val title = node.title ?: ""
    val due = node.dueDate?.let { DueDates.format(it) } ?: ""
    val link = node.linkUrl ?: ""
    val notes = node.notes ?: ""
    val person = node.person ?: ""
    div(classes = "edit-page") {
        // Buffer the text fields + their originals so Save can commit them together.
        attributes["data-signals"] =
            "{title: ${js(title)}, o_title: ${js(title)}, due: ${js(due)}, o_due: ${js(due)}, " +
                "link: ${js(link)}, o_link: ${js(link)}, notes: ${js(notes)}, o_notes: ${js(notes)}, " +
                "person: ${js(person)}, o_person: ${js(person)}, fopen: false}"

        // 1. Title (buffered).
        div(classes = "edit-field") {
            span("edit-label") { icon("pencil"); +" Title" }
            input(type = InputType.text) {
                attributes["data-bind:title"] = ""
                attributes["data-attr:data-changed"] = "\$title !== \$o_title ? 'true' : 'false'"
                attributes["value"] = title
            }
        }

        // 2. Context — chips (immediate).
        div(classes = "edit-field") {
            span("edit-label") { icon("tag"); +" Context" }
            div(classes = "chips-row") {
                read.contexts().forEach { c ->
                    val on = node.tags.any { it.toString() == c.id.toString() }
                    button(classes = if (on) "btn ctx chip-on" else "btn ctx") {
                        attributes["data-on:click"] =
                            "@post('/node/${node.id}/tag?context=${c.id}&on=${if (on) "false" else "true"}')"
                        +(c.name ?: "(context)")
                    }
                }
            }
        }

        // 3. Due date (buffered).
        div(classes = "edit-field") {
            span("edit-label") { icon("calendar"); +" Due" }
            input(type = InputType.date) {
                attributes["data-bind:due"] = ""
                attributes["data-attr:data-changed"] = "\$due !== \$o_due ? 'true' : 'false'"
                attributes["value"] = due
            }
        }

        // 4. Size — S/M/L chips (immediate).
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

        // 5. Link (buffered) + an open-anchor when set.
        div(classes = "edit-field") {
            span("edit-label") { icon("link"); +" Link" }
            input(type = InputType.text) {
                attributes["data-bind:link"] = ""
                attributes["data-attr:data-changed"] = "\$link !== \$o_link ? 'true' : 'false'"
                attributes["value"] = link
                attributes["placeholder"] = "https://…"
            }
            node.linkUrl?.let { url -> a(href = url) { icon("link"); +" open" } }
        }

        // 6. Description (buffered).
        div(classes = "edit-field") {
            span("edit-label") { icon("doc"); +" Description" }
            textArea {
                attributes["data-bind:notes"] = ""
                attributes["data-attr:data-changed"] = "\$notes !== \$o_notes ? 'true' : 'false'"
                attributes["placeholder"] = "Notes…"
                +notes
            }
        }

        // 7. Attachment / OCR / summary — read-only.
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

        // 8. Free-form tags — removable chips + add box (immediate; each is its own op).
        div(classes = "edit-field") {
            span("edit-label") { icon("tag"); +" Tags" }
            div(classes = "chips-row") {
                node.freeTags.forEach { t ->
                    button(classes = "btn chip-on") {
                        attributes["data-on:click"] = "@post('/node/${node.id}/freetag?on=false&label=' + encodeURIComponent('$t'))"
                        +"#$t ✕"
                    }
                }
            }
            // Single-word tags: space (or Enter) turns the word into a chip; autocomplete against
            // tags already in use. The existing chips above carry the ✕ that removes them.
            input(type = InputType.text) {
                attributes["data-bind:tag"] = ""
                attributes["placeholder"] = "Add a tag"
                attributes["list"] = "all-tags"
                attributes["autocomplete"] = "off"
                attributes["data-on:keydown"] =
                    "if ((evt.key === 'Enter' || evt.key === ' ') && \$tag.trim() !== '') { " +
                        "evt.preventDefault(); @post('/node/${node.id}/freetag?label=' + encodeURIComponent(\$tag.trim())); \$tag = '' }"
            }
            val allTags = read.nodes().flatMap { it.freeTags }.distinct().sorted()
            dataList {
                id = "all-tags"
                allTags.forEach { option { value = it } }
            }
        }

        // 9. Waiting-for person (buffered; recent-people datalist). Saved via /save → status WAITING.
        div(classes = "edit-field") {
            span("edit-label") { icon("waiting"); +" Waiting for" }
            input(type = InputType.text) {
                attributes["data-bind:person"] = ""
                attributes["data-attr:data-changed"] = "\$person !== \$o_person ? 'true' : 'false'"
                attributes["value"] = person
                attributes["placeholder"] = "Who?"
                attributes["list"] = "waiting-people"
            }
            val people = read.nodes().mapNotNull { it.person }.distinct().take(8)
            dataList {
                id = "waiting-people"
                people.forEach { option { value = it } }
            }
        }

        // 10. Subtasks — children as links + a quick-add (immediate).
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

        // 11. Comments — existing + a quick-add (immediate).
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

        // Bottom action row: immediate Delete/File/Done, then Cancel + Save for the buffered fields.
        div(classes = "actions") {
            button(classes = "btn") {
                attributes["data-key"] = "#"; attributes["data-act"] = "delete"
                attributes["data-on:click"] = "@post('/node/${node.id}/trash').then(() => location.href = '/')"
                icon("trash"); +" Delete"
            }
            button(classes = "btn") {
                attributes["data-key"] = "f"; attributes["data-act"] = "file"
                attributes["data-on:click"] = "\$fopen = !\$fopen"
                icon("folder"); +" File"
            }
            div(classes = "file-picker") {
                attributes["data-show"] = "\$fopen"
                fileSection(read, node, FileArea.PROJECTS, "Projects", null)
                fileSection(read, node, FileArea.REFERENCE, "Reference", WellKnownNodes.REFERENCE_ROOT)
            }
            button(classes = "btn") {
                attributes["data-key"] = "x"; attributes["data-act"] = "done"
                if (node.status == "DONE") {
                    attributes["data-on:click"] = "@post('/node/${node.id}/reopen')"
                    icon("check"); +" Undone"
                } else {
                    attributes["data-on:click"] = "@post('/node/${node.id}/complete')"
                    icon("check"); +" Done"
                }
            }
            // Cancel is a plain anchor (browser owns navigation, per the Tao); pending signal
            // edits are simply discarded when the page unloads.
            a(href = "/", classes = "btn") { icon("close"); +" Cancel" }
            button(classes = "btn save-btn") {
                attributes["data-on:click"] =
                    "@post('/node/${node.id}/save?title=' + encodeURIComponent(\$title) + '&notes=' + encodeURIComponent(\$notes) + " +
                        "'&due=' + encodeURIComponent(\$due) + '&link=' + encodeURIComponent(\$link) + '&person=' + encodeURIComponent(\$person))" +
                        ".then(() => location.href = '/')"
                icon("check"); +" Save"
            }
        }
    }
}
