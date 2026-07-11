package dev.njr.zync.web

import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.title

/**
 * The shared content UI (M6) — server-rendered kotlinx.html over the op-log projection,
 * served identically by the central server and the phone loopback. This scaffold renders
 * a placeholder shell; the inbox/tree/detail views + Datastar reactivity arrive in later
 * tasks.
 */
fun Route.webRoutes() {
    get("/") {
        call.respondHtml { shell() }
    }
    get("/health") {
        call.respondText("ok")
    }
}

/** The base page shell (layout grows in Task 3). */
fun HTML.shell() {
    head {
        title { +"zync" }
    }
    body {
        h1 { +"zync" }
        p { +"shared web module" }
    }
}
