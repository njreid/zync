package dev.njr.zync.web

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import dev.njr.zync.web.sse.ChangeNotifier
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The detail + reading views surface the OCR lifecycle chip and the
 * operator-written summary once the phone/operator have set those fields.
 */
class OcrSummaryViewTest {
    @Test
    fun detailAndReadingViewShowStatusAndSummary() = testApplication {
        val store = InMemoryStateStore()
        val emitter = RecordingEmitter(store)
        val commands = ContentCommands(emitter)
        val read = ContentReadModel(store)

        val doc = commands.createTask("Scanned document")
        // Pending OCR: the chip reads "OCR pending…", no summary yet.
        emitter.setField(doc, Fields.OCR_STATUS, JsonPrimitive("PENDING"))

        application {
            install(SSE)
            routing { webRoutes(read, changes = ChangeNotifier(), commands = commands) }
        }

        val pending = client.get("/node/$doc").bodyAsText()
        assertTrue(pending.contains("OCR pending…"), "expected pending chip: $pending")

        // OCR + summary land.
        emitter.setField(doc, Fields.OCR_STATUS, JsonPrimitive("DONE"))
        emitter.setField(doc, Fields.OCR_BLOB_HASH, JsonPrimitive("blob-" + "0".repeat(64)))
        emitter.setField(doc, Fields.SUMMARY, JsonPrimitive("An invoice for 500 dollars due in March."))

        val detail = client.get("/node/$doc").bodyAsText()
        assertTrue(detail.contains("OCR done"), "expected done chip: $detail")
        assertTrue(detail.contains("summary-label"), "expected summary block: $detail")
        assertTrue(detail.contains("An invoice for 500 dollars due in March."))

        val reading = client.get("/node/$doc/read").bodyAsText()
        assertTrue(reading.contains("An invoice for 500 dollars due in March."))
    }
}
