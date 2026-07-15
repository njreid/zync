package dev.njr.zync.web

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.stream.createHTML

actual object WebPlatform {
    actual fun asset(name: String): String =
        WebPlatform::class.java.classLoader.getResourceAsStream(name)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("$name not found on the classpath")

    actual fun assetBytes(name: String): ByteArray =
        WebPlatform::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
            ?: error("$name not found on the classpath")

    actual fun datastarRuntime(): String = asset("datastar.js")

    actual fun renderFragment(elementId: String, block: FlowContent.() -> Unit): String =
        createHTML().div {
            id = elementId
            block()
        }
}
