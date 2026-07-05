package dev.njr.zync.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.NodeEntity
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.ZyncDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DbSnapshotTest {

    @Test
    fun `snapshot yields a standalone db containing recent writes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snap-src-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, ZyncDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        db.nodeDao().insert(NodeEntity(kind = NodeKind.TASK, parentId = null, title = "snap me", createdAt = 1L))

        val dbFile = context.getDatabasePath(name)
        val target = File(context.cacheDir, "snap-out-${System.nanoTime()}.db")
        DbSnapshot.snapshot(db, dbFile, target)
        db.close()

        val raw = SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        raw.rawQuery("SELECT title FROM node WHERE title = ?", arrayOf("snap me")).use { c ->
            assertTrue("snapshot should contain the inserted row", c.moveToFirst())
            assertEquals("snap me", c.getString(0))
        }
        raw.close()
        target.delete()
    }
}
