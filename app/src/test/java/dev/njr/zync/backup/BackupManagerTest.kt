package dev.njr.zync.backup

import java.util.zip.ZipInputStream
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `archive contains db snapshot attachments and manifest`() {
        val source = fixture()
        val archive = source.manager.createArchive()

        val entries = unzip(archive)

        assertArrayEquals("db".toByteArray(), entries["zync.db"])
        // The live -wal/-shm sidecars must NOT be archived: beforeSnapshot folds
        // the WAL into the main file, and copying live sidecars would tear.
        assertFalse(entries.containsKey("zync.db-wal"))
        assertFalse(entries.containsKey("zync.db-shm"))
        assertArrayEquals("voice".toByteArray(), entries["attachments/attachments/aa/voice.m4a"])
        val manifest = entries["manifest.json"]!!.toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"databasePath\":\"zync.db\""))
        assertTrue(manifest.contains("\"relativePath\":\"attachments/aa/voice.m4a\""))
    }

    @Test
    fun `archive invokes checkpoint hook before reading database`() {
        val root = temp.newFolder()
        val db = root.resolve("zync.db").apply { writeBytes("before".toByteArray()) }
        val manager = BackupManager(
            dbFile = db,
            attachmentRoot = root.resolve("Documents/Zync"),
            beforeSnapshot = { db.writeBytes("after".toByteArray()) },
        )

        val entries = unzip(manager.createArchive())

        assertArrayEquals("after".toByteArray(), entries["zync.db"])
    }

    @Test
    fun `encrypted backup restores database and attachments`() {
        val source = fixture()
        val encrypted = source.manager.createEncryptedBackup("correct horse".toCharArray())
        val target = temp.newFolder("target")
        val targetManager = BackupManager(
            dbFile = target.resolve("zync.db"),
            attachmentRoot = target.resolve("Documents/Zync"),
        )

        targetManager.restoreEncryptedBackup(encrypted, "correct horse".toCharArray())

        assertArrayEquals("db".toByteArray(), target.resolve("zync.db").readBytes())
        // No sidecars in the archive, and restore clears any stale ones so Room
        // never reads an old -wal over the restored database.
        assertFalse(target.resolve("zync.db-wal").exists())
        assertFalse(target.resolve("zync.db-shm").exists())
        assertArrayEquals(
            "voice".toByteArray(),
            target.resolve("Documents/Zync/attachments/aa/voice.m4a").readBytes(),
        )
    }

    @Test(expected = AEADBadTagException::class)
    fun `wrong passphrase fails authentication`() {
        val encrypted = fixture().manager.createEncryptedBackup("right".toCharArray())

        fixture().manager.restoreEncryptedBackup(encrypted, "wrong".toCharArray())
    }

    @Test
    fun `restore rejects traversal attachment paths`() {
        val archive = zip(
            "manifest.json" to """
                {"version":1,"createdAt":1,"databasePath":"zync.db","attachments":[{"relativePath":"../escape","size":1}]}
            """.trimIndent().toByteArray(),
            "zync.db" to "db".toByteArray(),
            "attachments/../escape" to "x".toByteArray(),
        )
        val target = fixture()

        try {
            target.manager.restoreArchive(archive)
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    private fun fixture(): Fixture {
        val root = temp.newFolder()
        val db = root.resolve("zync.db").apply { writeBytes("db".toByteArray()) }
        root.resolve("zync.db-wal").writeBytes("wal".toByteArray())
        root.resolve("zync.db-shm").writeBytes("shm".toByteArray())
        val attachments = root.resolve("Documents/Zync")
        attachments.resolve("attachments/aa").mkdirs()
        attachments.resolve("attachments/aa/voice.m4a").writeBytes("voice".toByteArray())
        return Fixture(BackupManager(db, attachments, now = { 1L }))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private data class Fixture(val manager: BackupManager)
}
