package dev.njr.zync.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentDaoTest {
    private lateinit var db: ZyncDatabase

    @Before
    fun setUp() {
        db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert query and delete by node`() = runTest {
        val nodeId = db.nodeDao().insert(
            NodeEntity(
                kind = NodeKind.TASK,
                parentId = ZyncDatabase.INBOX_ID,
                title = "voice note",
                createdAt = 1L,
            )
        )
        val dao = db.attachmentDao()
        val first = dao.insert(AttachmentEntity(nodeId = nodeId, type = AttachmentType.AUDIO, relativePath = "a.m4a"))
        val second = dao.insert(AttachmentEntity(nodeId = nodeId, type = AttachmentType.TRANSCRIPT, relativePath = "a.txt"))

        assertEquals(
            listOf("a.m4a", "a.txt"),
            dao.forNode(nodeId).map { it.relativePath },
        )
        assertEquals(AttachmentType.AUDIO, dao.getById(first)!!.type)

        dao.delete(dao.getById(first)!!)
        assertEquals(listOf(second), dao.forNode(nodeId).map { it.id })

        dao.deleteForNode(nodeId)
        assertEquals(emptyList<AttachmentEntity>(), dao.forNode(nodeId))
    }

    @Test
    fun `deleting a node cascades attachments`() = runTest {
        val nodeId = db.nodeDao().insert(
            NodeEntity(
                kind = NodeKind.TASK,
                parentId = ZyncDatabase.INBOX_ID,
                title = "scan",
                createdAt = 1L,
            )
        )
        val attachmentId = db.attachmentDao().insert(
            AttachmentEntity(nodeId = nodeId, type = AttachmentType.PDF, relativePath = "scan.pdf")
        )

        db.openHelper.writableDatabase.execSQL("DELETE FROM node WHERE id = ?", arrayOf(nodeId))

        assertNull(db.nodeDao().getById(nodeId))
        assertNull(db.attachmentDao().getById(attachmentId))
    }
}
