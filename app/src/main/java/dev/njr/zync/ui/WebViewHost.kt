package dev.njr.zync.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.njr.zync.MainActivity
import dev.njr.zync.capture.CaptureSettingsBridge

/** The loopback URL the in-app WebView loads once at launch (per-boot token in the query). */
fun loopbackUrl(port: Int, token: String): String = "http://127.0.0.1:$port/?token=$token"

/**
 * Builds THE single WebView the app hosts for its whole lifetime: JavaScript + DOM storage on,
 * with the `ZyncCapture` bridge wired. Created parent-less so a Compose [AndroidView] can adopt
 * it without a reparent conflict. It must never be rebuilt — the loopback server connection the
 * WebView holds must persist for the life of the process (see [dev.njr.zync.ZyncApp]).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
fun createZyncWebView(
    activity: MainActivity,
    requestRecordAudio: ((Boolean) -> Unit) -> Unit,
): WebView = WebView(activity).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    addJavascriptInterface(CaptureSettingsBridge(activity, this, requestRecordAudio), "ZyncCapture")
}

/**
 * The native Compose shell: currently a full-screen host for the shared `:web` UI. Any future
 * native chrome (launcher affordances, settings entry points) hangs off this scaffold. The
 * `factory` returns the pre-built [webView], so recomposition never rebuilds it.
 */
@Composable
fun ZyncShell(webView: WebView) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}
