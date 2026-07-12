package dev.njr.zync.server

import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.ZyncApp
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZyncServerSmokeTest {
    @Test
    fun `starts on ephemeral loopback port, serves web with token, and stops`() {
        val app = ApplicationProvider.getApplicationContext<ZyncApp>()
        val server = ZyncServer(token = "smoke-token", content = app.webContent)
        val port = server.start()
        assertTrue(port in 1..65535)

        // "/" with a valid ?token= is authorized on the loopback and renders the :web shell
        val ok = URL("http://127.0.0.1:$port/?token=smoke-token").openConnection() as HttpURLConnection
        assertEquals(200, ok.responseCode)
        assertTrue(ok.inputStream.bufferedReader().readText().contains("zync"))

        // without a token the document route is rejected
        val denied = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        assertEquals(401, denied.responseCode)

        server.stop()
        val down = runCatching {
            (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 2000 }.responseCode
        }
        assertTrue(down.isFailure)
    }
}
