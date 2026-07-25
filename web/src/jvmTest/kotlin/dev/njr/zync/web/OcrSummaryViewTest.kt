package dev.njr.zync.web

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.views.nodeDetail
import dev.njr.zync.web.views.readingView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The detail + reading views surface the OCR lifecycle chip and the
 * operator-written summary once the phone/operator have set those fields.
 */
class OcrSummaryViewTest {
    @Test
    fun detailAndReadingViewShowStatusAndSummary() {
        val store = InMemoryStateStore()
        val emitter = RecordingEmitter(store)
        val commands = ContentCommands(emitter)
        val read = ContentReadModel(store)

        val doc = commands.createTask("Scanned document")
        // Pending OCR: the chip reads "OCR pending…", no summary yet.
        emitter.setField(doc, Fields.OCR_STATUS, JsonPrimitive("PENDING"))

        val pending = createHTML().div { nodeDetail(read, read.node(doc)!!) }
        assertTrue(pending.contains("OCR pending…"), "expected pending chip: $pending")

        // OCR + summary land.
        emitter.setField(doc, Fields.OCR_STATUS, JsonPrimitive("DONE"))
        emitter.setField(doc, Fields.OCR_BLOB_HASH, JsonPrimitive("blob-" + "0".repeat(64)))
        emitter.setField(doc, Fields.SUMMARY, JsonPrimitive("An invoice for 500 dollars due in March."))

        val detail = createHTML().div { nodeDetail(read, read.node(doc)!!) }
        assertTrue(detail.contains("OCR done"), "expected done chip: $detail")
        assertTrue(detail.contains("summary-label"), "expected summary block: $detail")
        assertTrue(detail.contains("An invoice for 500 dollars due in March."))

        val reading = createHTML().div { readingView(read.node(doc)!!, canChangeReadingState = false) }
        assertTrue(reading.contains("An invoice for 500 dollars due in March."))
    }
}
