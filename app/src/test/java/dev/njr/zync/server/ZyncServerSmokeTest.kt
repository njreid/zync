package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZyncServerSmokeTest {
    @Test fun `starts on ephemeral loopback port serves with token and stops`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = ZyncDatabase.inMemory(ctx)
        val server = ZyncServer(db, NodeRepository(db), "smoke-token", androidAssets(ctx.assets))
        val port = server.start()
        assertTrue(port in 1..65535)
        val ok = URL("http://127.0.0.1:$port/api/roots").openConnection() as HttpURLConnection
        ok.setRequestProperty(TOKEN_HEADER, "smoke-token")
        assertEquals(200, ok.responseCode)
        val denied = URL("http://127.0.0.1:$port/api/roots").openConnection() as HttpURLConnection
        assertEquals(401, denied.responseCode)
        server.stop()
        val down = runCatching {
            (URL("http://127.0.0.1:$port/api/roots").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 2000 }.responseCode
        }
        assertTrue(down.isFailure)
        db.close()
    }
}
