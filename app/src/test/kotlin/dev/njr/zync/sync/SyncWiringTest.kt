package dev.njr.zync.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.njr.zync.ZyncApp
import dev.njr.zync.replica.AndroidPairingStore
import dev.njr.zync.replica.Ed25519DeviceSigner
import dev.njr.zync.replica.PairedServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWiringTest {
    @Test
    fun pairingStoreRoundTrips() {
        val store = AndroidPairingStore(ApplicationProvider.getApplicationContext())
        store.clear()
        assertNull(store.load())

        val seed = Ed25519DeviceSigner.generateSeed()
        store.save(PairedServer("https://srv", ByteArray(32) { 7 }, "dev-1", seed))

        val loaded = store.load()!!
        assertEquals("https://srv", loaded.address)
        assertEquals("dev-1", loaded.deviceId)
        assertTrue(loaded.deviceSeed.contentEquals(seed))
        assertTrue(loaded.serverPublicKey.contentEquals(ByteArray(32) { 7 }))
    }

    @Test
    fun syncOnceIsNoOpWhenUnpaired() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ZyncApp>()
        AndroidPairingStore(app).clear()
        app.syncOnce() // no paired creds → returns without touching the network
    }

    @Test
    fun schedulerEnqueuesSyncWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SyncScheduler.requestSync(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("zync-sync-now").get()
        assertTrue(infos.isNotEmpty())
    }
}
