package dev.njr.zync.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZyncDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrate1To2_addsAllowedDevice_preservesNodes() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO node (id,kind,parentId,title,notes,status,deferUntil,createdAt,completedAt,sortOrder,builtin) " +
                "VALUES (100,'TASK',1,'survives','','ACTIVE',NULL,1,NULL,0,0)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migration_1_2).use { db ->
            val c = db.query("SELECT title FROM node WHERE id=100")
            assertTrue(c.moveToFirst()); assertTrue(c.getString(0) == "survives"); c.close()
            db.query("SELECT * FROM allowed_device").use { it } // table exists → no throw
        }
    }
    companion object { const val TEST_DB = "migration-test" }
}
