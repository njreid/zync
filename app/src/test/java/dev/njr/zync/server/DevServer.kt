package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Not part of the normal test suite. Hosts a real ZyncServer against an in-memory DB and
 * the packaged web assets so it can be driven by an external functional test harness
 * (see webtest/). Gated behind ZYNC_DEV_SERVER=1 so the default `test` task skips it.
 *
 * Run via: ZYNC_DEV_SERVER=1 ./gradlew :app:testDebugUnitTest --tests dev.njr.zync.server.DevServer
 */
@RunWith(RobolectricTestRunner::class)
class DevServer {
    @Test
    fun serve() {
        assumeTrue(System.getenv("ZYNC_DEV_SERVER") == "1")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = ZyncDatabase.inMemory(ctx)
        val server = ZyncServer(db, NodeRepository(db), "dev", androidAssets(ctx.assets), port = 8199)
        server.start()
        println("DEV SERVER READY http://127.0.0.1:8199/?token=dev")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
