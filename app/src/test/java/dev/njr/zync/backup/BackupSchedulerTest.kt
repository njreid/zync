package dev.njr.zync.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun active(name: String): Int =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .count { !it.state.isFinished }

    @Test
    fun `schedulePeriodic enqueues one periodic backup`() {
        BackupScheduler.schedulePeriodic(context)
        assertEquals(1, active(BackupScheduler.PERIODIC_WORK))
    }

    @Test
    fun `schedulePeriodic is idempotent (KEEP)`() {
        BackupScheduler.schedulePeriodic(context)
        BackupScheduler.schedulePeriodic(context)
        assertEquals(1, active(BackupScheduler.PERIODIC_WORK))
    }

    @Test
    fun `requestBackupSoon debounces bursts to a single pending run`() {
        BackupScheduler.requestBackupSoon(context)
        BackupScheduler.requestBackupSoon(context)
        BackupScheduler.requestBackupSoon(context)
        assertEquals(1, active(BackupScheduler.SOON_WORK))
    }

    @Test
    fun `cancelAll clears scheduled work`() {
        BackupScheduler.schedulePeriodic(context)
        BackupScheduler.requestBackupSoon(context)
        BackupScheduler.cancelAll(context)
        assertEquals(0, active(BackupScheduler.PERIODIC_WORK))
        assertEquals(0, active(BackupScheduler.SOON_WORK))
        // and the soon-work is genuinely finished/cancelled, not merely absent
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupScheduler.SOON_WORK).get()
        assertEquals(WorkInfo.State.CANCELLED, infos.first().state)
    }
}
