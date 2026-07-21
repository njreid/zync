package dev.njr.zync.web.views

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.title

/**
 * The fixed GTD surfaces (spec: "some fixed categories which need to be easily
 * accessible"). Rendered as a thumb-reachable bottom tab bar on every page so
 * Inbox / Next / Projects are one tap away from anywhere in the app.
 */
enum class Tab(val href: String, val label: String, val icon: String) {
    INBOX("/", "Inbox", "📥"),      // 📥
    NEXT("/next", "Next", "→"),           // →
    PROJECTS("/projects", "Projects", "🗂"), // 🗂
    REFERENCE("/reference", "Reference", "📁"), // 📁
    NONE("", "", ""),
}

/**
 * The shared page shell: a slim top bar (brand + optional Pairing), a `<main>` for the
 * view content, and a fixed bottom tab bar for the fixed GTD categories. Dark theme is
 * forced (`data-theme="dark"`, the v0.2 look); styles come from vendored stylesheet
 * FILES because the loopback CSP has no inline-style carve-out.
 */
fun HTML.page(
    pageTitle: String,
    settingsHref: String? = null,
    activeTab: Tab = Tab.NONE,
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
            strong { a(href = "/") { +"zync" } }
            // Server-only (the phone loopback has no pairing page to link to).
            settingsHref?.let { a(classes = "settings-link", href = it) { +"Pairing" } }
        }
        main(classes = "container") {
            // The inbox triage-panel open state is the Datastar `$exp` signal (holds the
            // expanded node id). It is created lazily on the first expand toggle and then
            // lives in Datastar's signal store — NOT the DOM — so it survives every #inbox
            // SSE morph (the open panel stays open). No `data-signals` declaration needed.
            content()
        }
        tabBar(activeTab)
    }
}

/** Fixed bottom navigation exposing the three GTD categories, active tab highlighted. */
private fun FlowContent.tabBar(active: Tab) {
    nav(classes = "tabbar") {
        listOf(Tab.INBOX, Tab.NEXT, Tab.PROJECTS, Tab.REFERENCE).forEach { tab ->
            a(href = tab.href, classes = if (tab == active) "tab active" else "tab") {
                if (tab == active) attributes["aria-current"] = "page"
                // g-chord key read from the DOM by the gesture helper.
                attributes["data-key"] = when (tab) {
                    Tab.INBOX -> "i"
                    Tab.NEXT -> "n"
                    Tab.PROJECTS -> "p"
                    Tab.REFERENCE -> "r"
                    Tab.NONE -> ""
                }
                span("tab-icon") { +tab.icon }
                span("tab-label") { +tab.label }
            }
        }
    }
}
