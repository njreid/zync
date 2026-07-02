package dev.njr.zync.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZyncDatabaseTest {
    private lateinit var db: ZyncDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = ZyncDatabase.inMemory(context)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `seeds inbox and someday builtin folders`() = runTest {
        val inbox = db.nodeDao().getById(ZyncDatabase.INBOX_ID)
        val someday = db.nodeDao().getById(ZyncDatabase.SOMEDAY_ID)
        assertNotNull(inbox); assertNotNull(someday)
        assertEquals("Inbox", inbox!!.title)
        assertEquals("Someday", someday!!.title)
        assertEquals(NodeKind.FOLDER, inbox.kind)
        assertTrue(inbox.builtin && someday!!.builtin)
    }

    @Test
    fun `node roundtrip with contexts and attachments`() = runTest {
        val taskId = db.nodeDao().insert(
            NodeEntity(kind = NodeKind.TASK, parentId = ZyncDatabase.INBOX_ID,
                title = "buy milk", createdAt = 1000L)
        )
        val ctxId = db.contextDao().insert(ContextEntity(name = "Errands"))
        db.contextDao().tag(NodeContextCrossRef(nodeId = taskId, contextId = ctxId))
        db.nodeDao().insertAttachment(
            AttachmentEntity(nodeId = taskId, type = AttachmentType.AUDIO,
                relativePath = "Inbox/rec1/audio.m4a")
        )
        val task = db.nodeDao().getById(taskId)!!
        assertEquals("buy milk", task.title)
        assertEquals(listOf("Errands"), db.contextDao().contextNamesFor(taskId))
        assertEquals(1, db.nodeDao().attachmentsFor(taskId).size)
    }
}
