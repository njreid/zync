package dev.njr.zync.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupWorkerFailureTest {
    @Test
    fun `user-action-required failures are not retried forever`() {
        assertTrue(IllegalStateException("Google Drive is not connected").isUserActionRequiredBackupFailure())
        assertTrue(IllegalStateException("Google Drive authorization needs user approval").isUserActionRequiredBackupFailure())
        assertTrue(IllegalStateException("backup passphrase required").isUserActionRequiredBackupFailure())
    }

    @Test
    fun `transient failures can retry`() {
        assertFalse(IllegalStateException("Drive request failed (503)").isUserActionRequiredBackupFailure())
    }
}
