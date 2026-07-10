package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.core.sync.BlobKeyResponse
import dev.njr.zync.server.blob.BlobService
import dev.njr.zync.server.blob.InMemoryBlobStore
import dev.njr.zync.server.blob.blobKey
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlobRoutesTest {
    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        val blobs = BlobService(InMemoryBlobStore(), maxBytes = 16)
        application { zyncModule(SyncService(JvmZyncDatabase.inMemory()), blobs = blobs) }
        block(client)
    }

    @Test
    fun uploadReturnsServerComputedKeyThenDownloads() = app { client ->
        val payload = "hi there".encodeToByteArray()
        val body = client.post("/blob") { setBody(payload) }.bodyAsText()
        val key = Json.decodeFromString(BlobKeyResponse.serializer(), body).key
        assertEquals(blobKey(payload), key) // server computed it from content

        val download = client.get("/blob/$key")
        assertEquals(HttpStatusCode.OK, download.status)
        assertTrue(download.readRawBytes().contentEquals(payload))
    }

    @Test
    fun missingBlobIs404() = app { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/blob/blob-${"0".repeat(64)}").status)
    }

    @Test
    fun malformedKeyIs404() = app { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/blob/not-a-valid-key").status)
    }

    @Test
    fun oversizedUploadIs413() = app { client ->
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/blob") { setBody(ByteArray(17)) }.status)
    }
}
