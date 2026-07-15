package dev.njr.zync

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.capture.DocScanActivity
import dev.njr.zync.capture.DocUploadActivity
import dev.njr.zync.capture.VoiceCaptureActivity
import dev.njr.zync.launcher.LauncherIntents
import dev.njr.zync.replica.PairingOutcome
import dev.njr.zync.ui.BarAction
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
        setContent { ZyncShell(webView, onBarAction = ::handleBarAction) }
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
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    /** Launcher action bar taps (spec L1): app slots fire intents; capture reuses the flows. */
    private fun handleBarAction(action: BarAction) {
        when (action) {
            BarAction.Messages -> launch(LauncherIntents.messages(), "No messages app found")
            BarAction.Calendar -> launch(LauncherIntents.calendar(), "No calendar app found")
            BarAction.Phone -> launch(LauncherIntents.phone(), "No dialer found")
            BarAction.CaptureText ->
                webView.evaluateJavascript("document.querySelector('.quick-add input')?.focus()", null)
            BarAction.CaptureVoice -> startActivity(Intent(this, VoiceCaptureActivity::class.java))
            BarAction.CaptureScan -> startActivity(Intent(this, DocScanActivity::class.java))
            BarAction.CaptureUpload -> startActivity(Intent(this, DocUploadActivity::class.java))
        }
    }

    private fun launch(intent: Intent, missing: String) {
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, missing, Toast.LENGTH_SHORT).show() }
    }

    /** A tapped/scanned `zync://pair` link (from the server's /settings/pairing page). */
    private fun handlePairingIntent(intent: Intent?) {
        val uri = intent?.dataString?.takeIf { it.startsWith("zync://pair") } ?: return
        intent.data = null // consume: don't re-pair on config-change redelivery
        Toast.makeText(this, "Pairing with server…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { (application as ZyncApp).pairFromUri(uri) }
            val message = when (outcome) {
                is PairingOutcome.Paired -> "Paired — syncing with ${outcome.server.address}"
                is PairingOutcome.Failed -> "Pairing failed: ${outcome.reason}"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}
