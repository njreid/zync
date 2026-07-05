package dev.njr.zync.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.attach.AttachmentStore
import dev.njr.zync.data.AttachmentType
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import dev.njr.zync.data.ZyncDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class NodeRepositoryAttachmentTest {
    private lateinit var db: ZyncDatabase
    private lateinit var repo: NodeRepository
    private lateinit var store: AttachmentStore
    private lateinit var root: File

    @Before
    fun setUp() {
        db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = NodeRepository(db) { 42L }
        root = Files.createTempDirectory("zync-repo-attach").toFile()
        store = AttachmentStore(root)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun `captureToInbox creates an active inbox task with the linked attachment`() = runTest {
        val bytes = "voice-bytes".toByteArray()
        val nodeId = repo.captureToInbox("Voice note", AttachmentType.AUDIO, bytes, "m4a", store)

        val node = repo.get(nodeId)!!
        assertEquals("Voice note", node.title)
        assertEquals(NodeKind.TASK, node.kind)
        assertEquals(ZyncDatabase.INBOX_ID, node.parentId)
        assertEquals(NodeStatus.ACTIVE, node.status)

        val attachments = repo.attachmentsFor(nodeId)
        assertEquals(1, attachments.size)
        assertEquals(AttachmentType.AUDIO, attachments[0].type)
        // The blob is actually on disk at the recorded relative path.
        assertArrayEquals(bytes, store.read(attachments[0].relativePath))
    }

    @Test
    fun `addAttachment links a derived transcript to an existing node`() = runTest {
        val nodeId = repo.captureToInbox("Scan", AttachmentType.PDF, "pdf-bytes".toByteArray(), "pdf", store)
        repo.addAttachment(nodeId, AttachmentType.OCR_TEXT, "recognized text".toByteArray(), "txt", store)

        val attachments = repo.attachmentsFor(nodeId)
        assertEquals(setOf(AttachmentType.PDF, AttachmentType.OCR_TEXT), attachments.map { it.type }.toSet())
    }

    @Test
    fun `addAttachment to an unknown node fails`() = runTest {
        try {
            repo.addAttachment(9999L, AttachmentType.AUDIO, "x".toByteArray(), "m4a", store)
            org.junit.Assert.fail("expected failure for unknown node")
        } catch (e: IllegalArgumentException) {
            // requireNode throws IllegalArgumentException("No node 9999")
        }
    }
}
