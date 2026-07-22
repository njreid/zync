package dev.njr.zync.attach

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A best-effort preview of a shared URL: the page `<title>` and its first text paragraph. */
data class LinkInfo(val title: String?, val paragraph: String?)

/**
 * Fetches a shared URL and extracts a lightweight preview (title + first paragraph) with plain
 * regex — no HTML parser dependency. Best-effort by design: JS-rendered pages return little
 * server-side markup, and it needs the phone to be online at share time; any failure → null.
 */
object LinkPreview {
    private val URL = Regex("""https?://[^\s<>"']+""")
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PARA = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")
    private const val MAX_PREVIEW = 400
    private const val MAX_HTML = 500_000

    /** The first http(s) URL in [text], or null. */
    fun firstUrl(text: String): String? = URL.find(text)?.value

    suspend fun fetch(url: String): LinkInfo? {
        val html = withTimeoutOrNull(8_000) {
            val http = HttpClient(OkHttp)
            try {
                val resp = http.get(url) { header("User-Agent", "zync-linkpreview/1.0") }
                if (resp.status.isSuccess()) resp.bodyAsText().take(MAX_HTML) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                http.close()
            }
        } ?: return null

        val title = TITLE.find(html)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() }
        val paragraph = PARA.findAll(html)
            .map { clean(it.groupValues[1]) }
            .firstOrNull { it.length >= 40 } // skip empty/menu <p>s
            ?.take(MAX_PREVIEW)
        return if (title == null && paragraph == null) null else LinkInfo(title, paragraph)
    }

    private fun clean(s: String): String =
        TAG.replace(s, " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
