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
    /**
     * Upload one blob by key. Returns false only when the bytes are missing locally
     * (nothing to upload — retrying cannot help). Throws [SyncRequestException] on an
     * HTTP failure (so the sync attempt aborts and retries) and [IllegalStateException]
     * if the server computed a different key for the same bytes (corruption — never
     * proceed to push the referencing op).
     */
    suspend fun upload(key: String): Boolean {
        val bytes = store.get(key) ?: return false
        val response = http.post("$baseUrl/blob") {
            signedHeaders(signer, "POST", "/blob", now(), nonce(), body = bytes).forEach { (k, v) -> header(k, v) }
            setBody(bytes)
        }
        response.requireOk("blob upload")
        val returned = json.decodeFromString(BlobKeyResponse.serializer(), response.bodyAsText()).key
        check(returned == key) { "server hashed blob to $returned, expected $key" }
        return true
    }

    /** Upload every key in [keys]; returns those confirmed uploaded. */
    suspend fun uploadAll(keys: Iterable<String>): List<String> = keys.filter { upload(it) }
}
