package dev.njr.zync.launcher

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live web suggestions for the search overlay, from the same suggest endpoint
 * Chrome's omnibox uses (`client=firefox` → plain JSON). Unofficial but stable
 * for a decade and what third-party launchers use — there is no official Android
 * API for web suggestions. Failures and slow answers degrade to no suggestions;
 * local app/settings results never wait on the network.
 */
object WebSuggest {
    private const val MAX = 4
    private val http by lazy { HttpClient(OkHttp) }

    suspend fun fetch(query: String): List<String> =
        withTimeoutOrNull(2_500) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8)
                parse(http.get("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded").bodyAsText())
            }.getOrNull()
        } ?: emptyList()

    /** Body shape: `["query", ["suggestion", …], …]`. */
    fun parse(body: String): List<String> =
        runCatching {
            Json.parseToJsonElement(body).jsonArray[1].jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.isNotBlank() }
                .take(MAX)
        }.getOrNull() ?: emptyList()
}
