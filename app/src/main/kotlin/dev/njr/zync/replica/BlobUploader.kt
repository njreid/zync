package dev.njr.zync.replica

import dev.njr.zync.core.sync.BlobKeyResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

/**
 * Uploads pending local blobs to the server `/blob` on sync (signed). The server
 * computes the key from the bytes and `putIfAbsent`-dedupes, so re-upload is safe; the
 * client asserts the returned key matches the content hash.
 */
class BlobUploader(
    private val http: HttpClient,
    private val baseUrl: String,
    private val store: LocalBlobStore,
    private val signer: DeviceSigner,
    private val now: () -> Long,
    private val nonce: () -> String,
    private val json: Json = Json,
) {
    /** Upload one blob by key; true if it was uploaded and the server agreed on the key. */
    suspend fun upload(key: String): Boolean {
        val bytes = store.get(key) ?: return false
        val response = http.post("$baseUrl/blob") {
            signedHeaders(signer, "POST", "/blob", now(), nonce()).forEach { (k, v) -> header(k, v) }
            setBody(bytes)
        }
        val returned = json.decodeFromString(BlobKeyResponse.serializer(), response.bodyAsText()).key
        return returned == key
    }

    /** Upload every key in [keys]; returns those confirmed uploaded. */
    suspend fun uploadAll(keys: Iterable<String>): List<String> = keys.filter { upload(it) }
}
