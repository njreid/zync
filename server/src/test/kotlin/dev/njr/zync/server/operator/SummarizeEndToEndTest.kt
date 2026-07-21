package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.server.id
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The summarize operator end-to-end against the fake LLM and an in-memory blob
 * store: once a scanned document's `ocrBlobHash` lands, the operator reads the
 * OCR *text* (expanded from the blob key by [OperatorPrompt]) and writes a
 * provenance-tagged `summary` into state and the sync feed.
 */
class SummarizeEndToEndTest {
    private val summarize = OperatorManifests.summarize()

    private fun harness(): OperatorHarness {
        val h = OperatorHarness(listOf(summarize))
        h.llm.default = { request ->
            // Echo the OCR text back so the test proves the blob text — not the
            // opaque hash — reached the prompt.
            val text = Regex("ocrBlobHash \\(text\\): (.*)").find(request.user)?.groupValues?.get(1) ?: "?"
            LlmReply.Text("""{"summary":"summary of: $text"}""")
        }
        return h
    }

    @Test
    fun ocr_text_landing_produces_a_summary() {
        val h = harness()
        val doc = id(1)
        val headBefore = h.service.bootstrap().headSeq
        val hash = h.putOcrText("Invoice total due 500 dollars by March.")

        h.push(h.scannedDoc(doc, at = 10, ocrBlobHash = hash))

        // One provenance-tagged summary op, seq-assigned, server-authored.
        val emitted = h.operatorOps().filterIsInstance<Op.SetField>()
        assertEquals(1, emitted.size)
        assertEquals(Fields.SUMMARY, emitted.single().field)
        assertEquals(Actor.Operator("summarize"), emitted.single().actor)
        assertEquals("server", emitted.single().deviceId)
        assertTrue(emitted.single().seq != null)

        // The blob TEXT reached the model (not the hash), and the summary merged.
        assertEquals(
            JsonPrimitive("summary of: Invoice total due 500 dollars by March."),
            h.service.stateStore.getRegister(RegisterKey(doc, Fields.SUMMARY))!!.value,
        )

        // Visible on the sync feed and recorded as handled.
        assertTrue(h.service.pull(since = headBefore).ops.any { it.actor is Actor.Operator })
        assertEquals(listOf("done"), h.runStatuses("summarize", doc))
    }

    @Test
    fun a_rescan_with_new_text_resummarizes_but_redelivery_does_not() {
        val h = harness()
        val doc = id(1)
        val first = h.putOcrText("First revision of the document.")
        h.push(h.scannedDoc(doc, at = 10, ocrBlobHash = first))
        assertEquals(1, h.llm.requests.size)

        // Redelivering the same ops re-fires nothing (same input version).
        h.redeliver(h.scannedDoc(doc, at = 10, ocrBlobHash = first))
        assertEquals(1, h.llm.requests.size)

        // A re-scan swaps in a new blob hash → new input version → re-summarize.
        val second = h.putOcrText("Second revision, materially different.")
        h.push(listOf(h.ops.setField(doc, Fields.OCR_BLOB_HASH, dev.njr.zync.server.str(second), dev.njr.zync.server.hlc(20))))
        assertEquals(2, h.llm.requests.size)
        assertEquals(
            JsonPrimitive("summary of: Second revision, materially different."),
            h.service.stateStore.getRegister(RegisterKey(doc, Fields.SUMMARY))!!.value,
        )
    }

    @Test
    fun no_summary_until_ocr_blob_hash_is_present() {
        val h = harness()
        val doc = id(1)
        // A titled node with no OCR yet is out of scope: nothing fires.
        h.push(listOf(h.ops.setField(doc, Fields.TITLE, dev.njr.zync.server.str("Scanned document"), dev.njr.zync.server.hlc(10))))
        assertEquals(0, h.llm.requests.size)
        assertTrue(h.operatorOps().isEmpty())
    }
}
