package dev.njr.zync.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.attach.AttachmentStore
import dev.njr.zync.data.AttachmentEntity
import dev.njr.zync.data.AttachmentType
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
import java.nio.file.Files
import java.util.TreeMap

@RunWith(RobolectricTestRunner::class)
class BackupJobTest {

    private class InMemoryBackupStore : BackupStore {
        val blobs = HashMap<String, ByteArray>()
        val manifests = TreeMap<String, ByteArray>()
        override suspend fun hasBlob(key: String) = blobs.containsKey(key)
        override suspend fun putBlob(key: String, bytes: ByteArray) { blobs[key] = bytes }
        override suspend fun getBlob(key: String) = blobs[key]
        override suspend fun listBlobKeys() = blobs.keys.toList()
        override suspend fun deleteBlob(key: String) { blobs.remove(key) }
        override suspend fun putManifest(id: String, bytes: ByteArray) { manifests[id] = bytes }
        override suspend fun getManifest(id: String) = manifests[id]
        override suspend fun listManifests() = manifests.keys.toList()
        override suspend fun deleteManifest(id: String) { manifests.remove(id) }
    }

    @Test
    fun `job backs up db plus attachments and restores them`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bkjob-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, ZyncDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

        // Seed a task + a real attachment blob on disk linked to it.
        val nodeId = db.nodeDao().insert(
            NodeEntity(kind = NodeKind.TASK, parentId = null, title = "with scan", createdAt = 1L)
        )
        val attachRoot = Files.createTempDirectory("bkjob-att").toFile()
        val store = AttachmentStore(attachRoot)
        val rel = store.write("PDF-BYTES".toByteArray(), AttachmentType.PDF, "pdf")
        db.nodeDao().insertAttachment(AttachmentEntity(nodeId = nodeId, type = AttachmentType.PDF, relativePath = rel))

        val backupStore = InMemoryBackupStore()
        val snapshot = SnapshotBackup(backupStore, BackupCrypto.newKey(), retain = 5) { 1000L }
        val workDir = Files.createTempDirectory("bkjob-work").toFile()
        val job = BackupJob(db, context.getDatabasePath(name), attachRoot, snapshot, workDir)

        val snapshotId = job.run()
        db.close()
        assertTrue(snapshotId.isNotBlank())

        // Restore into fresh locations and verify both the DB row and the file.
        val restoreDir = Files.createTempDirectory("bkjob-restore").toFile()
        val restoredDb = File(restoreDir, "zync.db")
        val restoredAttach = File(restoreDir, "attachments")
        assertTrue(snapshot.restore(null, restoredDb, restoredAttach))

        val raw = SQLiteDatabase.openDatabase(restoredDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        raw.rawQuery("SELECT title FROM node WHERE id = ?", arrayOf(nodeId.toString())).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("with scan", c.getString(0))
        }
        raw.close()
        assertEquals("PDF-BYTES", File(restoredAttach, rel).readText())

        attachRoot.deleteRecursively()
        workDir.deleteRecursively()
        restoreDir.deleteRecursively()
    }
}
