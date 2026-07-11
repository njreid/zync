package dev.njr.zync.web.sse

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.sse.ServerSSESession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A Datastar SSE event: an `event:` type + newline-joined `data:` lines. */
data class DatastarEvent(val event: String, val data: String)

/**
 * `datastar-patch-elements` — patch [html] into the DOM. Default [mode] `outer` morphs
 * matching ids; a [selector] targets a specific element. HTML is split across
 * `data: elements` lines, matching the Datastar v1 wire format.
 */
fun patchElementsEvent(html: String, selector: String? = null, mode: String = "outer"): DatastarEvent {
    val lines = buildList {
        if (mode != "outer") add("mode $mode")
        if (selector != null) add("selector $selector")
        html.trim().split("\n").forEach { add("elements $it") }
    }
    return DatastarEvent("datastar-patch-elements", lines.joinToString("\n"))
}

/** `datastar-patch-signals` — merge [signalsJson] into the client signal store. */
fun patchSignalsEvent(signalsJson: String): DatastarEvent =
    DatastarEvent("datastar-patch-signals", "signals $signalsJson")

/** Send a Datastar event over an SSE session. */
suspend fun ServerSSESession.patch(event: DatastarEvent) {
    send(event = event.event, data = event.data)
}

/** Respond to a Datastar fetch action with one-shot SSE patch events. */
suspend fun ApplicationCall.respondDatastar(vararg events: DatastarEvent) {
    val body = buildString {
        for (e in events) {
            append("event: ").append(e.event).append('\n')
            for (line in e.data.split('\n')) append("data: ").append(line).append('\n')
            append('\n')
        }
    }
    respondText(body, ContentType.parse("text/event-stream"))
}

/**
 * Broadcasts "the op-log state changed" so open SSE sessions re-render and patch. Both
 * surfaces call [notifyChanged] after applying ops (local mutation, sync pull, ingest).
 */
class ChangeNotifier {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val changes: SharedFlow<Unit> = flow

    fun notifyChanged() {
        flow.tryEmit(Unit)
    }
}
