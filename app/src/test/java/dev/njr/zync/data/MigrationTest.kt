package dev.njr.zync.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    @Test fun migrate2To3_addsAttachmentForeignKeyAndDropsOrphans() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO node (id,kind,parentId,title,notes,status,deferUntil,createdAt,completedAt,sortOrder,builtin) " +
                "VALUES (100,'TASK',1,'survives','','ACTIVE',NULL,1,NULL,0,0)")
            db.execSQL("INSERT INTO attachment (id,nodeId,type,relativePath) VALUES " +
                "(200,100,'PDF','scan.pdf'), (201,999,'AUDIO','orphan.m4a')")
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, Migration_2_3).use { db ->
            db.query("SELECT relativePath FROM attachment ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("scan.pdf", c.getString(0))
                assertTrue(!c.moveToNext())
            }

            db.query("PRAGMA foreign_key_list(`attachment`)").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("node", c.getString(2))
                assertEquals("nodeId", c.getString(3))
                assertEquals("id", c.getString(4))
                assertEquals("CASCADE", c.getString(6))
            }
        }
    }
    @Test fun migrate3To4_dropsContentTables_keepsAllowedDevice() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("INSERT INTO node (id,kind,parentId,title,notes,status,deferUntil,createdAt,completedAt,sortOrder,builtin) " +
                "VALUES (100,'TASK',1,'gone','','ACTIVE',NULL,1,NULL,0,0)")
            db.execSQL("INSERT INTO allowed_device (id,name,pubkey,addedAt,lastSeen,revoked) " +
                "VALUES (5,'phone','pk',1,NULL,0)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, Migration_3_4).use { db ->
            // the LAN-pairing allow-list survives the content cutover
            db.query("SELECT name FROM allowed_device WHERE id=5").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("phone", c.getString(0))
            }
            // the retired content tables are gone
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND " +
                "name IN ('node','context','node_context','attachment')").use { c ->
                assertTrue(!c.moveToFirst())
            }
        }
    }

    companion object { const val TEST_DB = "migration-test" }
}
