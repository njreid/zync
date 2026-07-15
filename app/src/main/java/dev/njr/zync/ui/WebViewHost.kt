package dev.njr.zync.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
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
    webViewClient = WebViewClientCompat()
    // Let :web content follow the system dark theme where it doesn't define its own. Guarded:
    // the feature check can be optimistic and a provider may still throw at call time (e.g. a
    // stub WebView under Robolectric), which must never crash app startup.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true) }
    }
    addJavascriptInterface(CaptureSettingsBridge(activity, this, requestRecordAudio), "ZyncCapture")
}

/**
 * The native Compose shell: the shared `:web` UI on top, the launcher action bar
 * (spec L1) pinned beneath it. The `factory` returns the pre-built [webView], so
 * recomposition never rebuilds it.
 */
@Composable
fun ZyncShell(webView: WebView, onBarAction: (BarAction) -> Unit = {}) {
    // safeDrawing keeps the :web UI clear of the status/nav bars, cutout, and IME under the
    // enforced edge-to-edge display (see MainActivity.enableEdgeToEdge).
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        ZyncActionBar(onBarAction)
    }
}
