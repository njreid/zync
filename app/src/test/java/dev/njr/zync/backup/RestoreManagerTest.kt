package dev.njr.zync.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.pairing.PasswordProtector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RestoreManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `restoreIfRequested restores latest backup and clears request`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = BackupSettings(context, FakeProtector())
        settings.savePassphrase("pw".toCharArray())
        settings.requestRestore()

        val source = temp.newFolder("source")
        val sourceDb = source.resolve("zync.db").apply { writeBytes("db".toByteArray()) }
        val sourceAttachments = source.resolve("Documents/Zync").apply { mkdirs() }
        val encrypted = BackupManager(sourceDb, sourceAttachments).createEncryptedBackup("pw".toCharArray())

        val target = temp.newFolder("target")
        val manager = BackupManager(target.resolve("zync.db"), target.resolve("Documents/Zync"))
        val restored = RestoreManager(
            context = context,
            settings = settings,
            manager = manager,
            drive = FakeDriveClient(encrypted),
        ).restoreIfRequested()

        assertTrue(restored)
        assertArrayEquals("db".toByteArray(), target.resolve("zync.db").readBytes())
        assertFalse(settings.state().restorePending)
    }

    @Test
    fun `restoreIfRequested is a no-op without request`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = BackupSettings(context, FakeProtector())

        val restored = RestoreManager(
            context = context,
            settings = settings,
            manager = BackupManager(temp.newFile("zync.db"), temp.newFolder("Documents")),
            drive = FakeDriveClient(null),
        ).restoreIfRequested()

        assertFalse(restored)
    }

    private class FakeDriveClient(private val latest: ByteArray?) : DriveClient {
        override suspend fun uploadBackup(name: String, bytes: ByteArray) = Unit
        override suspend fun latestBackup(): ByteArray? = latest
    }

    private class FakeProtector : PasswordProtector {
        override fun protect(plain: CharArray): ByteArray = String(plain).toByteArray()
        override fun unprotect(protected: ByteArray): CharArray = String(protected).toCharArray()
    }
}
