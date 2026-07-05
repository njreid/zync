package dev.njr.zync.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NodeRepositoryBackupTest {
    private lateinit var db: ZyncDatabase
    private var mutations = 0
    private lateinit var repo: NodeRepository

    @Before
    fun setUp() {
        db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = NodeRepository(db, onMutated = { mutations++ })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `mutating repository operations notify backup scheduler`() = runTest {
        val nodeId = repo.quickAddTask("capture me")
        repo.rename(nodeId, "captured")
        repo.setNotes(nodeId, "notes")
        val contextId = repo.createContext("Errands")
        repo.setContexts(nodeId, setOf(contextId))

        assertEquals(5, mutations)
    }
}
