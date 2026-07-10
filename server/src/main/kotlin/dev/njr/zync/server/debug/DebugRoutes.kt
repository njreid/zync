package dev.njr.zync.server.debug

import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.requireAuth
import dev.njr.zync.server.sync.SyncService
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * A tiny server-rendered view of current state + recent ops, to eyeball convergence
 * before the real shared web UI (M6). Auth-guarded.
 */
fun Route.debugRoutes(service: SyncService, auth: ServerAuth) {
    get("/debug") {
        if (!call.requireAuth(auth.authenticator)) return@get
        call.respondText(renderDebug(service), ContentType.Text.Html)
    }
}

private fun renderDebug(service: SyncService): String {
    val entities = service.state().values.sortedBy { it.entityId.toString() }
    val ops = service.recentOps(50)
    val sb = StringBuilder()
    sb.append("<!doctype html><html><head><meta charset=utf-8><title>zync debug</title>")
    sb.append("<style>body{font:14px system-ui;margin:2rem}table{border-collapse:collapse;margin:1rem 0}")
    sb.append("td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}.dead{color:#999;text-decoration:line-through}</style>")
    sb.append("</head><body><h1>zync server — debug</h1>")

    sb.append("<h2>Entities (").append(entities.size).append(")</h2>")
    sb.append("<table><tr><th>id</th><th>alive</th><th>parent</th><th>tags</th><th>fields</th></tr>")
    for (e in entities) {
        val cls = if (e.alive) "" else " class=dead"
        sb.append("<tr").append(cls).append("><td>").append(esc(e.entityId.toString()))
            .append("</td><td>").append(e.alive)
            .append("</td><td>").append(esc(e.parent?.toString() ?: "—"))
            .append("</td><td>").append(esc(e.tags.joinToString(", ") { it.toString() }))
            .append("</td><td>").append(esc(e.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }))
            .append("</td></tr>")
    }
    sb.append("</table>")

    sb.append("<h2>Recent ops (").append(ops.size).append(")</h2>")
    sb.append("<table><tr><th>seq</th><th>type</th><th>entity</th><th>hlc</th><th>actor</th></tr>")
    for (op in ops) {
        sb.append("<tr><td>").append(op.seq ?: "—")
            .append("</td><td>").append(esc(op::class.simpleName ?: "?"))
            .append("</td><td>").append(esc(op.entityId.toString()))
            .append("</td><td>").append(esc(op.hlc.pack()))
            .append("</td><td>").append(esc(op.actor.toString()))
            .append("</td></tr>")
    }
    sb.append("</table></body></html>")
    return sb.toString()
}

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
