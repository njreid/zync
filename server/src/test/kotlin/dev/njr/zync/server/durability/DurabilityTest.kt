package dev.njr.zync.server.durability

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.merge.project
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.data.SqlDelightStateStore
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.RandomOps
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurabilityTest {
    private val work = Files.createTempDirectory("zync-durability").toFile()
    private val dbPath = File(work, "zync.db").absolutePath
    private val remote = File(work, "remote")

    @AfterTest
    fun cleanup() {
        work.deleteRecursively()
    }

    /** Open a connection, run [block] against a state store, then close (flush WAL). */
    private fun <T> withDb(block: (ZyncDatabase) -> T): T {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        try {
            return block(JvmZyncDatabase.open(driver))
        } finally {
            driver.close()
        }
    }

    @Test
    fun backupWipeRestoreYieldsIdenticalState() {
        val gateway = FileCopyGateway(remote)
        val batch = RandomOps(5).batch(50)

        val expected = withDb { db ->
            val store = SqlDelightStateStore(db)
            batch.forEach { apply(it, store) }
            store.project()
        }
        gateway.snapshot(dbPath) // litestream replicates the current file

        // catastrophic loss of the box's disk
        listOf("", "-wal", "-shm").forEach { File(dbPath + it).delete() }
        assertTrue(!File(dbPath).exists())

        // boot on a fresh box: restore from replica, then open
        val restored = StartupSequence.open(dbPath, gateway)
        assertEquals(expected, SqlDelightStateStore(restored).project())
    }

    @Test
    fun freshBootCreatesAtCurrentSchemaVersion() {
        val db = StartupSequence.open(dbPath, DbBackupGateway.None)
        // schema version = 1 + number of .sqm migrations; a new migration bumps this
        assertEquals(4L, ZyncDatabase.Schema.version)
        // usable: a write/read round-trips
        val store = SqlDelightStateStore(db)
        apply(dev.njr.zync.server.Ops().setField(id(1), "title", str("hi"), hlc(10)), store)
        assertTrue(store.project().getValue(id(1)).alive)
    }

    @Test
    fun priorGenerationRecoversFromBadMigration() {
        val gateway = FileCopyGateway(remote)

        // generation 1: good state
        withDb { db -> apply(dev.njr.zync.server.Ops().setField(id(1), "title", str("good"), hlc(10)), SqlDelightStateStore(db)) }
        gateway.snapshot(dbPath)
        val goodGen = gateway.generation

        // a bad migration/mutation corrupts state; snapshot captures the bad generation too
        withDb { db -> apply(dev.njr.zync.server.Ops().setField(id(1), "title", str("corrupt"), hlc(20)), SqlDelightStateStore(db)) }
        gateway.snapshot(dbPath)

        // recover by restoring the prior good generation
        gateway.restoreGeneration(goodGen, dbPath)
        withDb { db ->
            assertEquals(str("good"), SqlDelightStateStore(db).project().getValue(id(1)).fields["title"])
        }
    }
}
