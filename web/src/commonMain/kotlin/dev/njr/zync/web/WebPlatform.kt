package dev.njr.zync.web

import kotlinx.html.FlowContent

/** Platform bits the shared web module needs but that aren't in `commonMain`. */
expect object WebPlatform {
    /** A vendored static asset (JS/CSS) from the module's resources — offline-safe. */
    fun asset(name: String): String

    /** A vendored binary asset (fonts) from the module's resources — offline-safe. */
    fun assetBytes(name: String): ByteArray

    /** The vendored Datastar JS runtime (served at `/assets/datastar.js`, offline-safe). */
    fun datastarRuntime(): String

    /** Render a `<div id=elementId>…</div>` fragment to a string (for SSE patches). */
    fun renderFragment(elementId: String, block: FlowContent.() -> Unit): String
}
