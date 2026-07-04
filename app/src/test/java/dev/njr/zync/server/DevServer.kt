package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.AllowedDeviceEntity
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.PairingService
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Not part of the normal test suite. Hosts a real ZyncServer against an in-memory DB and
 * the packaged web assets so it can be driven by an external functional test harness
 * (see webtest/). Gated behind ZYNC_DEV_SERVER=1 so the default `test` task skips it.
 *
 * A [PairingService] is wired in (with `remoteAccess` left unattached — there's no real
 * Android Keystore/NSD in this Robolectric-hosted harness, so `/remote/enable` correctly answers
 * 503 here) so the settings view's `/devices` and `/pair/approve` routes are actually mounted and
 * exercisable by webtest/. One allowed device is pre-seeded so the device list has something to
 * render without needing a full pairing handshake to run first.
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
        val pairing = PairingService(db.allowedDeviceDao(), randomNonce = { UUID.randomUUID().toString() })
        runBlocking {
            db.allowedDeviceDao().insert(
                AllowedDeviceEntity(
                    name = "Test Laptop",
                    pubkey = "dev-seed-pubkey",
                    addedAt = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                    revoked = false,
                ),
            )
        }
        val server = ZyncServer(
            db, NodeRepository(db), "dev", androidAssets(ctx.assets), port = 8199, pairing = pairing,
        )
        server.start()
        println("DEV SERVER READY http://127.0.0.1:8199/?token=dev")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
