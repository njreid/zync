package dev.njr.zync.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Upgrading from v0.2 leaves that app's Room database at the SAME path/filename the
 * op-log store opens (`zync.db`). Opening it as-is 500s every loopback render (wrong
 * user_version, no op-log tables) — the exact v0.3.0 white-screen bug. The factory
 * must purge a pre-oplog file (fresh start per the roadmap) while never touching a
 * real op-log database.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyDatabaseUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun legacyRoomFileIsPurgedAndReplacedWithAFreshOplogDb() {
        // Simulate the leftover v0.2 Room database: same filename, Room tables, version 4.
        val file = context.getDatabasePath("zync.db").also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL("CREATE TABLE nodes (id INTEGER PRIMARY KEY, title TEXT)")
            legacy.execSQL("INSERT INTO nodes(title) VALUES ('v0.2 task')")
            legacy.version = 4
        }

        // Without the purge this throws (can't downgrade 4→2 / no such table op_log).
        val db = AndroidZyncDatabase.create(context, "zync.db")

        assertTrue("fresh op-log store is usable", db.transportQueries.selectUnsynced().executeAsList().isEmpty())
        db.transportQueries.setCursor("server", 7)
        assertEquals(7L, db.transportQueries.getCursor("server").executeAsOne())
    }

    @Test
    fun realOplogDatabaseSurvivesReopen() {
        AndroidZyncDatabase.create(context, "zync.db").transportQueries.setCursor("server", 42)

        // Re-opening must NOT purge a genuine op-log database.
        val reopened = AndroidZyncDatabase.create(context, "zync.db")
        assertEquals(42L, reopened.transportQueries.getCursor("server").executeAsOne())
    }
}
