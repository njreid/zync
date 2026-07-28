package dev.njr.zync.attach

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A best-effort preview of a shared URL: title, first text paragraph, and a hero image URL. */
data class LinkInfo(val title: String?, val paragraph: String?, val image: String?)

/**
 * Fetches a shared URL and extracts a lightweight preview (title + first paragraph) with plain
 * regex — no HTML parser dependency. Best-effort by design: JS-rendered pages return little
 * server-side markup, and it needs the phone to be online at share time; any failure → null.
 */
object LinkPreview {
    private val URL = Regex("""https?://[^\s<>"']+""")
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val OG_TITLE = meta("og:title")
    private val PARA = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val OG_DESC = meta("og:description")
    private val OG_IMAGE = meta("og:image")
    private val TW_IMAGE = meta("twitter:image")
    private val TAG = Regex("<[^>]+>")

    /** A `<meta property|name="key" content="…">` matcher (attribute order agnostic). */
    private fun meta(key: String) = Regex(
        """<meta[^>]+(?:property|name)=["']${Regex.escape(key)}["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
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

        // Prefer Open Graph metadata (the site's own preview), fall back to <title>/<p>.
        val title = (OG_TITLE.find(html)?.groupValues?.get(1) ?: TITLE.find(html)?.groupValues?.get(1))
            ?.let(::clean)?.takeIf { it.isNotBlank() }
        val paragraph = (
            OG_DESC.find(html)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.length >= 20 }
                ?: PARA.findAll(html).map { clean(it.groupValues[1]) }.firstOrNull { it.length >= 40 } // skip menu <p>s
            )?.take(MAX_PREVIEW)
        val image = (OG_IMAGE.find(html) ?: TW_IMAGE.find(html))?.groupValues?.get(1)
            ?.let(::clean)?.takeIf { it.isNotBlank() }?.let { absolutize(it, url) }
        return if (title == null && paragraph == null && image == null) null else LinkInfo(title, paragraph, image)
    }

    /** Resolve a possibly-relative image src against the page [pageUrl]. */
    private fun absolutize(src: String, pageUrl: String): String = when {
        src.startsWith("http://") || src.startsWith("https://") -> src
        src.startsWith("//") -> (if (pageUrl.startsWith("https")) "https:" else "http:") + src
        src.startsWith("/") -> (Regex("""^(https?://[^/]+)""").find(pageUrl)?.groupValues?.get(1) ?: "") + src
        else -> src
    }

    private fun clean(s: String): String =
        TAG.replace(s, " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
