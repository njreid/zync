package dev.njr.zync.server

import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.sse.ChangeNotifier

/** The shared `:web` UI's read/write/notify surface, wired to the phone's op-log stack. */
class WebContent(
    val read: ContentReadModel,
    val commands: ContentCommands,
    val changes: ChangeNotifier,
)
