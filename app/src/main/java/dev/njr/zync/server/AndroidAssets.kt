package dev.njr.zync.server

import android.content.res.AssetManager
import io.ktor.http.ContentType

private val contentTypes = mapOf(
    "html" to ContentType.Text.Html,
    "js" to ContentType.parse("application/javascript"),
    "css" to ContentType.Text.CSS,
    "json" to ContentType.Application.Json,
    "svg" to ContentType.parse("image/svg+xml"),
    "png" to ContentType.Image.PNG,
    "woff2" to ContentType.parse("font/woff2"),
)

fun androidAssets(
    assetManager: AssetManager,
    root: String = "web",
): (String) -> Pair<ByteArray, ContentType>? = { path ->
    runCatching {
        assetManager.open("$root/$path").use { it.readBytes() }
    }.getOrNull()?.let { bytes ->
        bytes to (contentTypes[path.substringAfterLast('.', "")]
            ?: ContentType.Application.OctetStream)
    }
}
