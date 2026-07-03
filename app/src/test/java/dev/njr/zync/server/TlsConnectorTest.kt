package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.pairing.Crypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the dual-connector setup added for LAN HTTPS: a loopback HTTP connector plus an
 * HTTPS connector serving the self-signed cert from `Crypto.generateSelfSignedCert`.
 *
 * The test HTTPS client trusts all certs (test-only) — production pins the cert fingerprint
 * on the Tauri side instead of relying on normal CA trust.
 */
@RunWith(RobolectricTestRunner::class)
class TlsConnectorTest {

    @Test
    fun servesHttpsWithGeneratedCert_andHttpLoopbackStillWorks() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = ZyncDatabase.inMemory(ctx)
        val identity = Crypto.generateSelfSignedCert()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(identity.keyStoreBytes), identity.keyStorePassword)
        }
        val lan = LanConfig(
            keyStore = keyStore,
            keyStorePassword = identity.keyStorePassword,
            keyAlias = identity.keyAlias,
            host = "127.0.0.1",
            tlsPort = 0,
        )
        val server = ZyncServer(
            db,
            NodeRepository(db),
            "tls-token",
            androidAssets(ctx.assets),
            lan = lan,
        )
        try {
            val httpPort = server.start()
            assertTrue(httpPort in 1..65535)
            val tlsPort = server.tlsPort()
            assertTrue("expected a bound TLS port", tlsPort != null && tlsPort in 1..65535)

            // Loopback HTTP still works.
            val httpOk = java.net.URL("http://127.0.0.1:$httpPort/api/roots").openConnection()
                as java.net.HttpURLConnection
            httpOk.setRequestProperty(TOKEN_HEADER, "tls-token")
            assertEquals(200, httpOk.responseCode)

            // The HTTPS connector is live and serving the generated cert: prove the TLS
            // handshake succeeds and the request reaches the app (401 without a valid session
            // is expected — no device is paired in this test — the handshake itself is the
            // point).
            val trustAllClient = HttpClient(CIO) {
                engine {
                    https {
                        // The JVM resolves 127.0.0.1's canonical host name to "localhost" in
                        // some environments (e.g. via /etc/hosts), which the TLS client then
                        // uses for SNI/hostname verification against the certificate's SANs
                        // (127.0.0.1, 0.0.0.0). Pin the expected server name explicitly so the
                        // handshake verifies against the address we actually dialed.
                        serverName = "127.0.0.1"
                        trustManager = object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(
                                chain: Array<out java.security.cert.X509Certificate>?,
                                authType: String?,
                            ) = Unit

                            override fun checkServerTrusted(
                                chain: Array<out java.security.cert.X509Certificate>?,
                                authType: String?,
                            ) = Unit

                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                                arrayOf()
                        }
                    }
                }
            }
            runBlocking {
                val res = trustAllClient.get("https://127.0.0.1:$tlsPort/api/roots") {
                    header(TOKEN_HEADER, "wrong-token")
                }
                assertEquals(HttpStatusCode.Unauthorized, res.status)
            }
            trustAllClient.close()
        } finally {
            server.stop()
            db.close()
        }
    }
}
