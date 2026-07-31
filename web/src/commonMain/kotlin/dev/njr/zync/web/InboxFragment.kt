package dev.njr.zync.web

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.views.inboxSection

/**
 * The inbox section as a standalone `#inbox` fragment — the same markup the `/` route and the
 * `/updates` SSE render. Exposed so the server's `/capture` can return it and the desktop capture
 * bar swaps `#inbox` in place, keeping the `FlowContent` DSL (and its kotlinx-html dependency)
 * inside `:web`.
 */
fun inboxFragmentHtml(read: ContentReadModel, inbox: Ulid?, now: Long, context: Ulid?): String =
    WebPlatform.renderFragment("inbox") { inboxSection(read, inbox, now, context) }
