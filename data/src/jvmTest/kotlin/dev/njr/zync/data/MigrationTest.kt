package dev.njr.zync.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.njr.zync.data.db.ZyncDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the migration harness: a database left at an older `PRAGMA user_version` is
 * migrated forward by [JvmZyncDatabase.open], preserving existing rows. One test per
 * migration step — add a vN→v(N+1) test alongside each new `.sqm`.
 */
class MigrationTest {
    private fun query(driver: JdbcSqliteDriver, sql: String): List<String?> =
        driver.executeQuery(null, sql, { cursor ->
            val out = mutableListOf<String?>()
            while (cursor.next().value) out += cursor.getString(0)
            QueryResult.Value(out.toList())
        }, 0).value

    @Test
    fun v1DeviceTableMigratesToV2WithNullReplicaId() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Hand-built v1 shape of the table 1.sqm touches, with a pre-migration row.
        driver.execute(
            null,
            """CREATE TABLE device (
                 device_id TEXT NOT NULL PRIMARY KEY,
                 public_key TEXT NOT NULL,
                 revoked INTEGER NOT NULL DEFAULT 0,
                 paired_at INTEGER NOT NULL
               )""",
            0,
        )
        driver.execute(null, "INSERT INTO device(device_id, public_key, paired_at) VALUES ('d1', 'pk', 42)", 0)
        driver.execute(null, "PRAGMA user_version = 1", 0)

        val db = JvmZyncDatabase.open(driver)

        assertEquals(listOf(ZyncDatabase.Schema.version.toString()), query(driver, "PRAGMA user_version"))
        val row = db.deviceQueries.getDevice("d1").executeAsOne()
        assertEquals("pk", row.public_key)
        assertNull(row.replica_id, "pre-binding pairings migrate with a NULL replica id")
    }
}
