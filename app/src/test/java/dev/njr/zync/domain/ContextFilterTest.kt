package dev.njr.zync.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.ZyncDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContextFilterTest {
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

    @Test fun `finds directly tagged and ancestor-inherited tasks at any depth`() = runTest {
        val errands = repo.createContext("Errands")
        val folder = repo.createNode(NodeKind.FOLDER, null, "Home")
        val project = repo.createNode(NodeKind.PROJECT, folder, "Renovation")
        val direct = repo.quickAddTask("buy paint")
        repo.setContexts(direct, setOf(errands))
        val parent = repo.createNode(NodeKind.TASK, project, "shop run")
        repo.setContexts(parent, setOf(errands))
        val inherited = repo.createNode(NodeKind.TASK, parent, "get bolts")   // inherits from parent
        val unrelated = repo.quickAddTask("write report")

        val titles = repo.observeTasksInContext(errands).first().map { it.title }.toSet()
        assertEquals(setOf("buy paint", "shop run", "get bolts"), titles)
    }

    @Test fun `excludes done dropped and deferred tasks`() = runTest {
        val errands = repo.createContext("Errands")
        val done = repo.quickAddTask("done one"); repo.setContexts(done, setOf(errands)); repo.complete(done)
        val dropped = repo.quickAddTask("dropped one"); repo.setContexts(dropped, setOf(errands)); repo.trash(dropped)
        val deferred = repo.quickAddTask("later one"); repo.setContexts(deferred, setOf(errands))
        repo.setDefer(deferred, 99_999L)   // clock is 1_000 → hidden
        val live = repo.quickAddTask("now one"); repo.setContexts(live, setOf(errands))

        val titles = repo.observeTasksInContext(errands).first().map { it.title }
        assertEquals(listOf("now one"), titles)
    }

    @Test fun `setContexts replaces the tag set`() = runTest {
        val a = repo.createContext("A"); val b = repo.createContext("B")
        val t = repo.quickAddTask("t")
        repo.setContexts(t, setOf(a))
        repo.setContexts(t, setOf(b))
        assertEquals(listOf("B"), db.contextDao().contextNamesFor(t))
    }
}
