package dev.njr.zync.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRunnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `backup uploads encrypted archive to drive`() = runTest {
        val fixture = fixture()
        val drive = FakeDriveClient()
        val runner = BackupRunner(fixture.manager, drive, { "pw".toCharArray() }, now = { 42L })

        val result = runner.backupNow()

        assertTrue(result is BackupRunResult.Uploaded)
        assertEquals("zync-42.zyncbackup", (result as BackupRunResult.Uploaded).name)
        assertEquals("zync-42.zyncbackup", drive.uploads.single().first)
        assertTrue(drive.uploads.single().second.isNotEmpty())
    }

    @Test
    fun `restore pulls latest backup from drive`() = runTest {
        val source = fixture()
        val encrypted = source.manager.createEncryptedBackup("pw".toCharArray())
        val targetRoot = temp.newFolder()
        val target = BackupManager(targetRoot.resolve("zync.db"), targetRoot.resolve("Documents/Zync"))
        val runner = BackupRunner(target, FakeDriveClient(latest = encrypted), { "pw".toCharArray() })

        assertTrue(runner.restoreLatest())

        assertArrayEquals("db".toByteArray(), targetRoot.resolve("zync.db").readBytes())
    }

    @Test
    fun `missing passphrase skips backup and restore`() = runTest {
        val drive = FakeDriveClient()
        val runner = BackupRunner(fixture().manager, drive, { null })

        assertEquals(BackupRunResult.Skipped, runner.backupNow())
        assertFalse(runner.restoreLatest())
        assertEquals(emptyList<Pair<String, ByteArray>>(), drive.uploads)
    }

    private fun fixture(): Fixture {
        val root = temp.newFolder()
        val db = root.resolve("zync.db").apply { writeBytes("db".toByteArray()) }
        val attachments = root.resolve("Documents/Zync")
        attachments.mkdirs()
        return Fixture(BackupManager(db, attachments))
    }

    private data class Fixture(val manager: BackupManager)

    private class FakeDriveClient(private val latest: ByteArray? = null) : DriveClient {
        val uploads = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun uploadBackup(name: String, bytes: ByteArray) {
            uploads += name to bytes
        }

        override suspend fun latestBackup(): ByteArray? = latest
    }
}
