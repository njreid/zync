package dev.njr.zync

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.capture.CaptureSettingsBridge
import dev.njr.zync.pairing.QrScanBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(QrScanBridge(this@MainActivity, this), "ZyncNative")
            addJavascriptInterface(CaptureSettingsBridge(this@MainActivity), "ZyncCapture")
        }
        setContentView(webView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        val app = application as ZyncApp
        // Force the lazy `remoteAccess` manager into existence (and wired into
        // `pairingService.remoteAccess`, see ZyncApp) up front, so the settings view's
        // `/remote/*` routes are functional as soon as the WebView loads, not only after
        // whatever first touches `app.remoteAccess` on some other path.
        app.remoteAccess
        lifecycleScope.launch(Dispatchers.IO) {
            val port = app.ensureServerStarted()
            withContext(Dispatchers.Main) {
                webView.loadUrl("http://127.0.0.1:$port/?token=${app.serverToken}")
            }
        }
    }
}
