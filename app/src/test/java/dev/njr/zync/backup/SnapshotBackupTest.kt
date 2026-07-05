package dev.njr.zync.backup

import dev.njr.zync.data.AttachmentEntity
import dev.njr.zync.data.AttachmentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TreeMap

/** In-memory [BackupStore] standing in for Drive. */
private class InMemoryBackupStore : BackupStore {
    val blobs = HashMap<String, ByteArray>()
    val manifests = TreeMap<String, ByteArray>() // keys sorted ascending
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

class SnapshotBackupTest {
    private lateinit var work: File
    private lateinit var attachmentsRoot: File
    private val key = BackupCrypto.newKey()
    private val store = InMemoryBackupStore()

    @Before
    fun setUp() {
        work = Files.createTempDirectory("zync-backup").toFile()
        attachmentsRoot = File(work, "attachments").apply { mkdirs() }
    }

    @After
    fun tearDown() { work.deleteRecursively() }

    private fun writeAttachment(relativePath: String, content: String): AttachmentEntity {
        val f = File(attachmentsRoot, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return AttachmentEntity(nodeId = 1, type = AttachmentType.AUDIO, relativePath = relativePath)
    }

    private fun dbFile(content: String): File =
        File(work, "zync.db").apply { writeText(content) }

    @Test
    fun `backup then restore reproduces db and attachments`() = runTest {
        val db = dbFile("DB-V1")
        val a1 = writeAttachment("audio/aa/one.m4a", "audio-one")
        val a2 = writeAttachment("pdf/bb/two.pdf", "pdf-two")
        val backup = SnapshotBackup(store, key, retain = 5) { 1000L }

        backup.backUp(db, attachmentsRoot, listOf(a1, a2))

        val restoreDir = File(work, "restore").apply { mkdirs() }
        val restoredDb = File(restoreDir, "zync.db")
        val restoredAttach = File(restoreDir, "attachments")
        assertTrue(backup.restore(null, restoredDb, restoredAttach))

        assertArrayEquals(db.readBytes(), restoredDb.readBytes())
        assertEquals("audio-one", File(restoredAttach, "audio/aa/one.m4a").readText())
        assertEquals("pdf-two", File(restoredAttach, "pdf/bb/two.pdf").readText())
    }

    @Test
    fun `unchanged content is not re-uploaded across snapshots`() = runTest {
        val db = dbFile("DB")
        val a1 = writeAttachment("audio/aa/one.m4a", "same")
        var clock = 1000L
        val backup = SnapshotBackup(store, key, retain = 5) { clock }

        backup.backUp(db, attachmentsRoot, listOf(a1))
        val blobsAfterFirst = store.blobs.size // db + 1 attachment = 2

        clock = 2000L
        backup.backUp(db, attachmentsRoot, listOf(a1)) // nothing changed
        assertEquals("no new blobs when nothing changed", blobsAfterFirst, store.blobs.size)
        assertEquals("but a new snapshot exists", 2, store.manifests.size)
    }

    @Test
    fun `changing one attachment uploads exactly one new blob`() = runTest {
        val db = dbFile("DB")
        val a1 = writeAttachment("audio/aa/one.m4a", "keep")
        var clock = 1000L
        val backup = SnapshotBackup(store, key, retain = 5) { clock }
        backup.backUp(db, attachmentsRoot, listOf(a1))
        val before = store.blobs.size

        clock = 2000L
        val a2 = writeAttachment("audio/cc/three.m4a", "new-content")
        backup.backUp(db, attachmentsRoot, listOf(a1, a2))
        assertEquals("only the one new attachment blob is added", before + 1, store.blobs.size)
    }

    @Test
    fun `retention prunes old snapshots and gcs orphaned blobs`() = runTest {
        val backup = SnapshotBackup(store, key, retain = 2) { clock }
        // three snapshots, each with a unique db + unique attachment
        for (i in 1..3) {
            clock = i * 1000L
            val db = dbFile("DB-$i")
            val a = writeAttachment("audio/v/$i.m4a", "content-$i")
            backup.backUp(db, attachmentsRoot, listOf(a))
        }
        assertEquals("only newest 2 snapshots retained", 2, store.manifests.size)
        // snapshot 1's unique blobs (DB-1 + content-1) must be gc'd; 2 and 3 remain.
        assertEquals("2 snapshots x (db + attachment) = 4 blobs", 4, store.blobs.size)

        // the oldest retained (snapshot 2) still restores correctly
        val oldest = backup.snapshots().first()
        val out = File(work, "r2").apply { mkdirs() }
        assertTrue(backup.restore(oldest, File(out, "zync.db"), File(out, "att")))
        assertEquals("DB-2", File(out, "zync.db").readText())
        assertEquals("content-2", File(out, "att/audio/v/2.m4a").readText())
    }

    @Test
    fun `restore returns false when there are no snapshots`() = runTest {
        val backup = SnapshotBackup(store, key) { 1L }
        assertFalse(backup.restore(null, File(work, "x.db"), File(work, "x")))
    }

    private var clock = 1000L
}
