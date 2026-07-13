package dev.njr.zync

import android.Manifest
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.ui.ZyncShell
import dev.njr.zync.ui.createZyncWebView
import dev.njr.zync.ui.loopbackUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var recordAudioResult: ((Boolean) -> Unit)? = null
    private val recordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            recordAudioResult?.invoke(granted)
            recordAudioResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is enforced from Android 15 (targetSdk 36); the shell applies the
        // safe-drawing insets so the :web UI isn't drawn under the status/navigation bars.
        enableEdgeToEdge()
        // The single WebView is created once and hosted by the Compose shell; it must never be
        // rebuilt, so the loopback server connection it holds survives for the process life.
        webView = createZyncWebView(this) { callback ->
            recordAudioResult = callback
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { ZyncShell(webView) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        val app = application as ZyncApp
        lifecycleScope.launch(Dispatchers.IO) {
            val port = app.ensureServerStarted()
            withContext(Dispatchers.Main) {
                webView.loadUrl(loopbackUrl(port, app.serverToken))
            }
        }
    }
}
