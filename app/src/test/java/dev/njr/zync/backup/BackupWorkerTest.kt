package dev.njr.zync.backup

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackupWorkerTest {
    @Test
    fun `periodic backup request has network and battery constraints`() {
        val request = BackupWorker.periodicRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.DAYS.toMillis(1), request.workSpec.intervalDuration)
    }

    @Test
    fun `debounced backup request waits before running`() {
        val request = BackupWorker.debouncedRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.MINUTES.toMillis(5), request.workSpec.initialDelay)
    }
}
