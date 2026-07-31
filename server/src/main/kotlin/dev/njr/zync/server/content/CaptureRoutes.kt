package dev.njr.zync.server.content

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.BlobTooLargeException
import dev.njr.zync.web.inboxFragmentHtml
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Desktop quick-capture (browser-session gated — `/capture` isn't in the web session-exempt list).
 * The bottom bar POSTs a title + dropped files as multipart; we create ONE inbox item (title = the
 * text, or the first filename if blank) and attach every file's content-addressed blob to it.
 *
 * Server-only, because it needs the blob store — the shared `:web` bar is inert on the phone
 * loopback (and CSS-hidden on touch there anyway). Attachments are ordinary `AddAttachment` ops,
 * so they sync to replicas exactly like phone-captured ones.
 */
fun Route.captureRoutes(content: ServerContent, blobs: BlobService) {
    post("/capture") {
        var title = ""
        val files = mutableListOf<CapturedFile>()
        val multipart = call.receiveMultipart()
        while (true) {
            val part = multipart.readPart() ?: break
            when (part) {
                is PartData.FormItem -> if (part.name == "title") title = part.value.trim()
                is PartData.FileItem -> {
                    val name = part.originalFileName?.takeIf { it.isNotBlank() } ?: "attachment"
                    val type = part.contentType?.toString() ?: name.substringAfterLast('.', "file")
                    files += CapturedFile(name, type, part.provider().readRemaining().readByteArray())
                }
                else -> {}
            }
            part.release()
        }
        if (title.isBlank() && files.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val node = content.commands.createTask(title.ifBlank { files.first().name }, null)
        try {
            files.forEach { f -> content.commands.attach(node, blobs.store(f.bytes), f.type, f.name) }
        } catch (e: BlobTooLargeException) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        content.changes.notifyChanged()
        // Return the freshly-rendered inbox fragment so the bar can swap #inbox in place (live
        // updates here ride the mutation RESPONSE, matching every other :web mutation — the
        // /updates SSE only pushes on connect). The bar honours the same context cookie the page did.
        val raw = call.request.queryParameters["context"] ?: call.request.cookies["zync_context"]
        val context = if (raw.isNullOrBlank() || raw == "none") null else runCatching { Ulid.parse(raw) }.getOrNull()
        val html = inboxFragmentHtml(content.read, null, Long.MAX_VALUE, context)
        call.respondText(html, ContentType.Text.Html)
    }
}

private class CapturedFile(val name: String, val type: String, val bytes: ByteArray)
