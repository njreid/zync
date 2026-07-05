package dev.njr.zync.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.pairing.PasswordProtector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("zync_backup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `state starts disabled without passphrase`() {
        val state = BackupSettings(context, FakeProtector()).state()

        assertFalse(state.enabled)
        assertFalse(state.hasPassphrase)
        assertFalse(state.restorePending)
        assertNull(state.lastSuccessAt)
    }

    @Test
    fun `passphrase is protected and restored`() {
        val settings = BackupSettings(context, FakeProtector())

        settings.savePassphrase("secret".toCharArray())

        assertTrue(settings.state().hasPassphrase)
        assertArrayEquals("secret".toCharArray(), settings.passphrase())
    }

    @Test
    fun `success and failure status are recorded`() {
        val settings = BackupSettings(context, FakeProtector())

        settings.setEnabled(true)
        settings.recordFailure(10L, "boom")
        settings.recordSuccess(20L, "zync-20.zyncbackup")

        val state = settings.state()
        assertTrue(state.enabled)
        assertEquals(20L, state.lastSuccessAt)
        assertEquals(10L, state.lastFailureAt)
        assertEquals("zync-20.zyncbackup", state.lastBackupName)
        assertNull(state.lastError)
    }

    @Test
    fun `restore request is persisted and cleared`() {
        val settings = BackupSettings(context, FakeProtector())

        settings.requestRestore()
        assertTrue(settings.state().restorePending)

        settings.clearRestoreRequest()
        assertFalse(settings.state().restorePending)
    }

    private class FakeProtector : PasswordProtector {
        override fun protect(plain: CharArray): ByteArray =
            String(plain).reversed().toByteArray(Charsets.UTF_8)

        override fun unprotect(protected: ByteArray): CharArray =
            String(protected, Charsets.UTF_8).reversed().toCharArray()
    }
}
