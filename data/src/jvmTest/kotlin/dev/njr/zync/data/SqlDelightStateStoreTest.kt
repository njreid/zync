package dev.njr.zync.data

import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.data.db.ZyncDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightStateStoreTest {

    @Test
    fun matchesInMemoryReferenceOverRandomBatches() {
        for (seed in 1..25) {
            val batch = RandomOps(seed).batch(count = 60)
            val reference = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }
            val sql = SqlDelightStateStore(JvmZyncDatabase.inMemory()).apply { batch.forEach { apply(it, this) } }
            assertEquals(reference.project(), sql.project(), "projection divergence at seed=$seed")
            assertEquals(reference.allParents(), sql.allParents(), "parent divergence at seed=$seed")
        }
    }

    @Test
    fun idempotentReapplyMatchesReference() {
        val batch = RandomOps(99).batch(count = 40)
        val reference = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }
        val sql = SqlDelightStateStore(JvmZyncDatabase.inMemory()).apply {
            batch.forEach { apply(it, this) }
            batch.forEach { apply(it, this) } // re-deliver everything
        }
        assertEquals(reference.project(), sql.project())
    }

    @Test
    fun statePersistsAcrossReopen() {
        val path = File.createTempFile("zync-test", ".db").apply { delete() }.absolutePath
        try {
            val batch = RandomOps(7).batch(count = 30)
            val reference = InMemoryStateStore().apply { batch.forEach { apply(it, this) } }

            // write, then drop the reference to the first connection
            SqlDelightStateStore(JvmZyncDatabase.file(path)).apply { batch.forEach { apply(it, this) } }

            // reopen the same file — schema must NOT be recreated, state must survive
            val reopened = SqlDelightStateStore(JvmZyncDatabase.file(path))
            assertEquals(reference.project(), reopened.project())
            assertTrue(reopened.project().isNotEmpty())
        } finally {
            File(path).delete()
        }
    }

    @Test
    fun schemaVersionBaselineForMigrationHarness() {
        // Bumping this is the trigger to add a .sqm migration + a v(N-1)->vN test
        // (see MigrationTest). v2: device.replica_id (pairing→replica binding).
        assertEquals(7L, ZyncDatabase.Schema.version)
    }
}
