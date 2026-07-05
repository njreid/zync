package dev.njr.zync.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleDriveClientTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Request>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery.orEmpty(),
                authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                body = exchange.requestBody.readBytes(),
            )
            handle(exchange)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `upload writes multipart backup into appDataFolder`() = runTest {
        val client = client()

        client.uploadBackup("zync-1.zyncbackup", "encrypted".toByteArray())

        val request = requests.first()
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files", request.path)
        assertEquals("uploadType=multipart", request.query)
        assertEquals("Bearer token", request.authorization)
        assertTrue(request.contentType.startsWith("multipart/related; boundary=zync-"))
        val body = request.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"name\":\"zync-1.zyncbackup\""))
        assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(body.contains("encrypted"))
        assertEquals("/drive/v3/files", requests[1].path)
    }

    @Test
    fun `latestBackup lists appDataFolder and downloads newest file`() = runTest {
        val client = client()

        assertArrayEquals("backup bytes".toByteArray(), client.latestBackup())

        assertEquals("/drive/v3/files", requests[0].path)
        assertTrue(requests[0].query.contains("spaces=appDataFolder"))
        assertTrue(requests[0].query.contains("orderBy=modifiedTime%20desc"))
        assertEquals("/drive/v3/files/file-1", requests[1].path)
        assertEquals("alt=media", requests[1].query)
    }

    @Test
    fun `latestBackup returns null when appDataFolder has no backups`() = runTest {
        val client = client()

        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery.orEmpty(), "", "", ByteArray(0))
            respond(exchange, 200, """{"files":[]}""".toByteArray())
        }

        assertEquals(null, client.latestBackup())
        assertEquals(1, requests.size)
    }

    @Test
    fun `upload prunes backups older than the last five`() = runTest {
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery.orEmpty(),
                authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                body = exchange.requestBody.readBytes(),
            )
            when {
                exchange.requestURI.path == "/upload/drive/v3/files" ->
                    respond(exchange, 200, """{"id":"uploaded"}""".toByteArray())
                exchange.requestURI.path == "/drive/v3/files" -> {
                    val files = (1..7).joinToString(",") { """{"id":"file-$it","name":"zync-$it.zyncbackup"}""" }
                    respond(exchange, 200, """{"files":[$files]}""".toByteArray())
                }
                exchange.requestMethod == "DELETE" -> respond(exchange, 204, ByteArray(0))
                else -> respond(exchange, 404, "not found".toByteArray())
            }
        }

        client().uploadBackup("zync-new.zyncbackup", "encrypted".toByteArray())

        val deleted = requests.filter { it.method == "DELETE" }.map { it.path }
        assertEquals(listOf("/drive/v3/files/file-6", "/drive/v3/files/file-7"), deleted)
    }

    private fun client(): GoogleDriveClient =
        GoogleDriveClient(
            context = ApplicationProvider.getApplicationContext<Context>(),
            tokenProvider = { "token" },
            baseUrl = "http://127.0.0.1:${server.address.port}",
        )

    private fun handle(exchange: HttpExchange) {
        when {
            exchange.requestURI.path == "/upload/drive/v3/files" ->
                respond(exchange, 200, """{"id":"uploaded"}""".toByteArray())
            exchange.requestURI.path == "/drive/v3/files" ->
                respond(exchange, 200, """{"files":[{"id":"file-1","name":"zync-1.zyncbackup"}]}""".toByteArray())
            exchange.requestURI.path == "/drive/v3/files/file-1" ->
                respond(exchange, 200, "backup bytes".toByteArray())
            exchange.requestMethod == "DELETE" ->
                respond(exchange, 204, ByteArray(0))
            else -> respond(exchange, 404, "not found".toByteArray())
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, bytes: ByteArray) {
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val authorization: String,
        val contentType: String,
        val body: ByteArray,
    )
}
