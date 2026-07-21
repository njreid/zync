package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.OcrStatus
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The OCR pipeline against a fake Drive transport: it stores the OCR text as a
 * blob + `ocr_text` attachment (so the blob-before-op sync uploads it), sets
 * `ocrBlobHash`/`ocrStatus`, deletes nothing on failure, and reports the right
 * WorkManager outcome for transient vs permanent failures.
 */
@RunWith(RobolectricTestRunner::class)
class OcrProcessorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MutableClock(var ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    /** Records each call; returns [text] or throws [error]. */
    private class FakeDriveOcr(val text: String = "OCR TEXT", val error: DriveOcrException? = null) : DriveOcr {
        val calls = mutableListOf<Pair<Int, String>>()
        override suspend fun ocr(bytes: ByteArray, sourceMime: String): String {
            calls += bytes.size to sourceMime
            error?.let { throw it }
            return text
        }
    }

    private class Fixture(
        val store: SqlDelightStateStore,
        val blobs: LocalBlobStore,
        val writer: OpWriter,
        val db: dev.njr.zync.data.db.ZyncDatabase,
    )

    private fun fixture(): Fixture {
        val db = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)
        val store = SqlDelightStateStore(db)
        val clock = MutableClock(1000)
        val writer = OpWriter(db, store, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(3))
        return Fixture(store, LocalBlobStore(tmp.newFolder("blobs")), writer, db)
    }

    /** Capture a scan: node + PDF attachment, returning (node, scanBlobHash). */
    private fun captureScan(f: Fixture): Pair<Ulid, String> {
        val node = f.writer.createNode("Scanned document")
        val bytes = "pdf-bytes".encodeToByteArray()
        val hash = f.blobs.put(bytes)
        f.writer.createAttachment(node, "pdf", hash, "capture.pdf")
        return node to hash
    }

    @Test
    fun successStoresOcrTextBlobAttachmentAndFields() = runBlocking {
        val f = fixture()
        val (node, scanHash) = captureScan(f)
        val drive = FakeDriveOcr(text = "Invoice due March.")
        val processor = OcrProcessor(f.blobs, f.writer, drive)

        val outcome = processor.process(node, scanHash, "application/pdf")
        assertEquals(OcrProcessor.Outcome.DONE, outcome)
        assertEquals(1, drive.calls.size)

        val snap = f.store.project().getValue(node)
        assertEquals(JsonPrimitive(OcrStatus.DONE), snap.fields[Fields.OCR_STATUS])
        val ocrHash = (snap.fields[Fields.OCR_BLOB_HASH] as JsonPrimitive).content
        // The OCR text was stored under that hash…
        assertEquals("Invoice due March.", f.blobs.get(ocrHash)!!.decodeToString())
        // …and paired with an ocr_text attachment so the blob-before-op sync uploads it.
        val ocrAttachment = f.store.project().values.first { snapshot ->
            (snapshot.fields["@attachment"]?.jsonObject?.get("type") as? JsonPrimitive)?.content == "ocr_text"
        }
        assertEquals(ocrHash, (ocrAttachment.fields.getValue("@attachment").jsonObject.getValue("blobHash") as JsonPrimitive).content)
    }

    @Test
    fun transientFailureRetriesAndLeavesStatusRunning() = runBlocking {
        val f = fixture()
        val (node, scanHash) = captureScan(f)
        val drive = FakeDriveOcr(error = DriveOcrException(permanent = false, "network"))
        val processor = OcrProcessor(f.blobs, f.writer, drive)

        assertEquals(OcrProcessor.Outcome.RETRY, processor.process(node, scanHash, "application/pdf"))
        val snap = f.store.project().getValue(node)
        assertEquals(JsonPrimitive(OcrStatus.RUNNING), snap.fields[Fields.OCR_STATUS])
        assertNull(snap.fields[Fields.OCR_BLOB_HASH]) // no text, no summarize trigger
    }

    @Test
    fun permanentFailureMarksFailed() = runBlocking {
        val f = fixture()
        val (node, scanHash) = captureScan(f)
        val drive = FakeDriveOcr(error = DriveOcrException(permanent = true, "consent"))
        val processor = OcrProcessor(f.blobs, f.writer, drive)

        assertEquals(OcrProcessor.Outcome.FAILED, processor.process(node, scanHash, "application/pdf"))
        assertEquals(JsonPrimitive(OcrStatus.FAILED), f.store.project().getValue(node).fields[Fields.OCR_STATUS])
    }

    @Test
    fun pendingScansFindsUnprocessedScansOnly() {
        val f = fixture()
        val (pending, pendingHash) = captureScan(f)
        // A second scan that's already been OCR'd should be excluded.
        val (done, _) = captureScan(f)
        f.writer.setField(done, Fields.OCR_STATUS, JsonPrimitive(OcrStatus.DONE))

        val refs = OcrProcessor.pendingScans(f.store)
        assertTrue(refs.any { it.nodeId == pending })
        assertFalse(refs.any { it.nodeId == done })
        // and the pending ref carries the scan blob to feed Drive
        assertEquals(pendingHash, refs.first { it.nodeId == pending }.scanBlobHash)
    }
}
