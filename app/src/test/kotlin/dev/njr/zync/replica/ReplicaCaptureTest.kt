package dev.njr.zync.replica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.merge.project
import dev.njr.zync.data.AndroidZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class ReplicaCaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MutableClock(var ms: Long) : Clock { override fun nowMillis() = ms }
    private class FakeHlcStore : HlcStore {
        var value: Hlc? = null
        override fun load() = value
        override fun save(hlc: Hlc) { value = hlc }
    }

    private fun setup(): Triple<SqlDelightStateStore, ReplicaCapture, dev.njr.zync.data.db.ZyncDatabase> {
        val db = AndroidZyncDatabase.create(ApplicationProvider.getApplicationContext<Context>(), name = null)
        val store = SqlDelightStateStore(db)
        val clock = MutableClock(1000)
        val writer = OpWriter(db, store, LocalHlc(FakeHlcStore(), "phone", clock), "phone", clock, Random(9))
        val inbox = writer.createNode("Inbox")
        val capture = ReplicaCapture(writer, LocalBlobStore(tmp.newFolder("blobs")), inbox = { inbox })
        return Triple(store, capture, db)
    }

    @Test
    fun noteCaptureCreatesInboxNodeOffline() {
        val (store, capture, db) = setup()
        val node = capture.captureNote("Call dentist", notes = "before Friday")

        val snap = store.project().getValue(node)
        assertTrue(snap.alive)
        assertEquals(JsonPrimitive("Call dentist"), snap.fields["title"])
        assertEquals(JsonPrimitive("before Friday"), snap.fields["notes"])
        // filed under the inbox
        assertEquals(store.project().entries.first { it.value.fields["title"] == JsonPrimitive("Inbox") }.key, snap.parent)
        // queued for sync (nothing pushed yet — offline)
        assertTrue(db.transportQueries.selectUnsynced().executeAsList().isNotEmpty())
    }

    @Test
    fun attachmentCaptureStoresBlobAndLinksIt() {
        val (store, capture, _) = setup()
        val bytes = "photo-bytes".encodeToByteArray()
        val node = capture.captureAttachment("Receipt", bytes, type = "image", filename = "receipt.jpg")

        // the attachment entity references the node + the content hash
        val attachment = store.project().values.first { it.fields.containsKey("@attachment") }
        val payload = attachment.fields.getValue("@attachment").jsonObject
        assertEquals(node.toString(), payload.getValue("nodeId").let { (it as JsonPrimitive).content })
        assertEquals(blobKeyOf(bytes), payload.getValue("blobHash").let { (it as JsonPrimitive).content })
        assertTrue(attachment.alive)
    }
}
