package dev.njr.zync.web.views

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.strong
import kotlinx.html.title
import kotlinx.html.ul

/**
 * The shared page shell: Pico-styled nav + a `<main>` for the view content.
 * Dark theme is forced (`data-theme="dark"`, the v0.2 look); styles come from
 * vendored stylesheet FILES because the loopback CSP has no inline-style carve-out.
 */
fun HTML.page(pageTitle: String, settingsHref: String? = null, content: FlowContent.() -> Unit) {
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
    }
    body {
        nav {
            ul { li { strong { a(href = "/") { +"zync" } } } }
            ul {
                li { a(href = "/") { +"Inbox" } }
                li { a(href = "/tree") { +"Tree" } }
                // Server-only (the phone loopback has no pairing page to link to).
                settingsHref?.let { li { a(href = it) { +"Pairing" } } }
            }
        }
        main(classes = "container") { content() }
    }
}
