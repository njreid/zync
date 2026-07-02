package dev.njr.zync.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import dev.njr.zync.data.ZyncDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NodeRepositoryTest {
    private lateinit var db: ZyncDatabase
    private lateinit var repo: NodeRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = NodeRepository(db) { clock }
    }

    @After
    fun tearDown() = db.close()

    @Test fun `quickAddTask lands in inbox`() = runTest {
        val id = repo.quickAddTask("buy milk")
        val task = repo.get(id)!!
        assertEquals(ZyncDatabase.INBOX_ID, task.parentId)
        assertEquals(NodeKind.TASK, task.kind)
        assertEquals(1_000L, task.createdAt)
    }

    @Test fun `createNode rejects illegal nesting`() = runTest {
        val taskId = repo.quickAddTask("t")
        try {
            repo.createNode(NodeKind.PROJECT, taskId, "p under task")
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            repo.createNode(NodeKind.TASK, null, "task at root")
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun `move enforces rules and refuses cycles`() = runTest {
        val folder = repo.createNode(NodeKind.FOLDER, null, "Work")
        val project = repo.createNode(NodeKind.PROJECT, folder, "Website")
        val a = repo.createNode(NodeKind.TASK, project, "a")
        val b = repo.createNode(NodeKind.TASK, a, "b")
        repo.move(b, project)                 // legal
        assertEquals(project, repo.get(b)!!.parentId)

        // Now move b back under a and try to move a under b (should fail — cycle)
        repo.move(b, a)
        try {
            repo.move(a, b) // a under its own descendant
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            repo.move(ZyncDatabase.INBOX_ID, folder) // builtin immovable
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun `complete stamps completedAt`() = runTest {
        val id = repo.quickAddTask("t"); clock = 2_000L
        repo.complete(id)
        val task = repo.get(id)!!
        assertEquals(NodeStatus.DONE, task.status)
        assertEquals(2_000L, task.completedAt)
        repo.reopen(id)
        assertEquals(NodeStatus.ACTIVE, repo.get(id)!!.status)
        assertNull(repo.get(id)!!.completedAt)
    }

    @Test fun `convertTaskToProject flips kind and reparents children stay`() = runTest {
        val folder = repo.createNode(NodeKind.FOLDER, null, "Work")
        val taskId = repo.quickAddTask("plan party")
        val sub = repo.createNode(NodeKind.TASK, taskId, "book venue")
        repo.convertTaskToProject(taskId, folder)
        val converted = repo.get(taskId)!!
        assertEquals(NodeKind.PROJECT, converted.kind)
        assertEquals(folder, converted.parentId)
        assertEquals(taskId, repo.get(sub)!!.parentId)   // subtree untouched
    }

    @Test fun `convertTaskToProject requires folder target`() = runTest {
        val t1 = repo.quickAddTask("a"); val t2 = repo.quickAddTask("b")
        try {
            repo.convertTaskToProject(t1, t2)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun `trash drops node but refuses builtin`() = runTest {
        val id = repo.quickAddTask("junk")
        repo.trash(id)
        assertEquals(NodeStatus.DROPPED, repo.get(id)!!.status)
        try {
            repo.trash(ZyncDatabase.SOMEDAY_ID)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNotNull(repo.get(ZyncDatabase.SOMEDAY_ID))
    }

    @Test fun `builtin folders cannot be renamed`() = runTest {
        try {
            repo.rename(ZyncDatabase.INBOX_ID, "My stuff")
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
