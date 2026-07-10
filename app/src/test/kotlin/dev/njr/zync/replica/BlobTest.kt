package dev.njr.zync.replica

import dev.njr.zync.core.sync.BlobKeyResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlobTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json
    private val seed = ByteArray(32) { (it + 2).toByte() }
    private val signer = Ed25519DeviceSigner("phone", seed)

    @Test
    fun keyMatchesTheServerAlgorithm() {
        // golden: sha256("") — locks the phone key format to the server's
        assertEquals("blob-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", blobKeyOf(ByteArray(0)))
    }

    @Test
    fun putIsContentAddressedAndIdempotent() {
        val store = LocalBlobStore(tmp.newFolder("blobs"))
        val bytes = "attachment".encodeToByteArray()
        val k1 = store.put(bytes)
        val k2 = store.put(bytes)
        assertEquals(k1, k2)
        assertEquals(blobKeyOf(bytes), k1)
        assertTrue(store.has(k1))
        assertTrue(store.get(k1)!!.contentEquals(bytes))
        assertFalse(store.has("blob-" + "0".repeat(64)))
    }

    @Test
    fun uploadPostsBytesAndAgreesOnKey() = runBlocking {
        val store = LocalBlobStore(tmp.newFolder("blobs"))
        val key = store.put("hi there".encodeToByteArray())

        var receivedBytes: ByteArray? = null
        var signed = false
        val http = HttpClient(MockEngine { request ->
            receivedBytes = request.body.toByteArray()
            signed = request.headers["X-Signature"] != null && request.headers["X-Device-Id"] == "phone"
            // server computes the key from the bytes (same algorithm)
            respond(json.encodeToString(BlobKeyResponse.serializer(), BlobKeyResponse(blobKeyOf(receivedBytes!!))), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })

        val uploader = BlobUploader(http, "https://srv", store, signer, now = { 1000 }, nonce = { "n1" })
        assertTrue(uploader.upload(key))
        assertTrue(signed)
        assertTrue(receivedBytes!!.contentEquals("hi there".encodeToByteArray()))
        // uploading a missing key is a no-op
        assertFalse(uploader.upload("blob-" + "0".repeat(64)))
    }
}
