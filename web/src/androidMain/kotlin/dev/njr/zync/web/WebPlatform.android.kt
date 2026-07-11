package dev.njr.zync.web

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.stream.createHTML

actual object WebPlatform {
    actual fun datastarRuntime(): String =
        WebPlatform::class.java.classLoader?.getResourceAsStream("datastar.js")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("datastar.js not found on the classpath")

    actual fun renderFragment(elementId: String, block: FlowContent.() -> Unit): String =
        createHTML().div {
            id = elementId
            block()
        }
}
