package dev.njr.zync.web.views

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.title

/** The shared page shell: header/nav + a `<main>` for the view content. */
fun HTML.page(pageTitle: String, content: FlowContent.() -> Unit) {
    head {
        title { +"zync — $pageTitle" }
        // Datastar runtime, vendored + served locally so the phone loopback works offline.
        script(type = "module", src = "/assets/datastar.js") {}
    }
    body {
        header {
            a(href = "/") { +"zync" }
            nav {
                a(href = "/") { +"Inbox" }
                a(href = "/tree") { +"Tree" }
            }
        }
        main { content() }
    }
}
