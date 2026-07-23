package dev.njr.zync.web.views

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContextView
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.summary
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

/**
 * The fixed GTD surfaces (spec: "some fixed categories which need to be easily accessible"),
 * now exposed through the top-bar **View** dropdown (left) rather than a bottom tab bar.
 */
enum class Tab(val href: String, val label: String, val key: String) {
    INBOX("/", "Inbox", "i"),
    TODAY("/today", "Today", "t"),
    NEXT("/next", "Next", "n"),
    PROJECTS("/projects", "Projects", "p"),
    REFERENCE("/reference", "Reference", "r"),
    NONE("", "", "");

    companion object {
        /** The menu order for the View dropdown. */
        val VIEWS = listOf(INBOX, TODAY, NEXT, PROJECTS, REFERENCE)
    }
}

/**
 * The shared page shell: a slim top bar with a **View** dropdown (left) and a **Context**
 * dropdown (right, mirroring the home screen's @context), and a `<main>` for the view content.
 * Dark theme is forced (`data-theme="dark"`, the v0.2 look); styles come from vendored FILES
 * because the loopback CSP has no inline-style carve-out. Both dropdowns are pure
 * `<details>`/`<summary>` — no JS, CSP-safe.
 */
fun HTML.page(
    pageTitle: String,
    settingsHref: String? = null,
    activeTab: Tab = Tab.NONE,
    contexts: List<ContextView> = emptyList(),
    selectedContext: Ulid? = null,
    content: FlowContent.() -> Unit,
) {
    attributes["lang"] = "en"
    attributes["data-theme"] = "dark"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +"zync — $pageTitle" }
        link(rel = "stylesheet", href = "/assets/pico.min.css")
        link(rel = "stylesheet", href = "/assets/custom.css")
        // Datastar runtime, vendored + served locally so the phone loopback works offline.
        script(type = "module", src = "/assets/datastar.js") {}
        // Gesture + keyboard layer (swipe complete/delete, j/k cursor, g-chords).
        script(type = "module", src = "/assets/zync-gestures.js") {}
    }
    body {
        nav(classes = "topbar") {
            viewMenu(activeTab, settingsHref)
            contextMenu(activeTab, contexts, selectedContext)
        }
        main(classes = "container") {
            // List search: '/' reveals + focuses this box; the gesture layer filters visible rows.
            input(type = InputType.search, classes = "list-search") { attributes["placeholder"] = "Filter…" }
            // The item expand state is the Datastar `$exp` signal (holds the expanded node id),
            // created lazily on first toggle and living in Datastar's store — NOT the DOM — so it
            // survives every SSE morph.
            content()
        }
        // Desktop-only keyboard cheatsheet (toggled by '?'; hidden on touch — see custom.css).
        div(classes = "kbd-help") {
            unsafe {
                +("<b>Keys</b><br>j/k move · o expand · x done · # delete · e edit · f file · s snooze · " +
                    "w waiting · Shift+J/K reorder · / search · g then i/t/n/p/r · ? this")
            }
        }
    }
}

/** Left dropdown: the fixed views. Links carry `data-key` so the g-chords still work. */
private fun FlowContent.viewMenu(active: Tab, settingsHref: String?) {
    details(classes = "menu view-menu") {
        summary { +(active.takeIf { it != Tab.NONE }?.label ?: "Views") }
        ul {
            Tab.VIEWS.forEach { tab ->
                li {
                    a(href = tab.href, classes = if (tab == active) "active" else null) {
                        attributes["data-key"] = tab.key
                        +tab.label
                    }
                }
            }
            // Server-only pairing/settings link (null on the phone loopback).
            settingsHref?.let { li { a(href = it) { +"Pairing" } } }
        }
    }
}

/**
 * Right dropdown: the context filter, mirroring the home screen's @context. Selecting a context
 * navigates the CURRENT view with `?context=` (the server persists it in a cookie).
 */
private fun FlowContent.contextMenu(active: Tab, contexts: List<ContextView>, selected: Ulid?) {
    val base = active.href.ifBlank { "/" }
    val current = contexts.firstOrNull { it.id.toString() == selected?.toString() }
    details(classes = "menu context-menu") {
        summary { +(current?.name ?: "All") }
        ul {
            li { a(href = "$base?context=none") { +"All" } }
            contexts.forEach { c ->
                li { a(href = "$base?context=${c.id}") { +(c.name ?: "(context)") } }
            }
        }
    }
}
