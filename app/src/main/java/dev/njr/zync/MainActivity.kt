package dev.njr.zync

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.backup.BackupAuthBridge
import dev.njr.zync.backup.BackupScheduler
import dev.njr.zync.backup.RestoreManager
import dev.njr.zync.capture.CaptureSettingsBridge
import dev.njr.zync.pairing.QrScanBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var backupAuthBridge: BackupAuthBridge
    private var recordAudioResult: ((Boolean) -> Unit)? = null
    private val googleDriveSignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::backupAuthBridge.isInitialized) backupAuthBridge.handleSignInResult(it.data)
    }
    private val recordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            recordAudioResult?.invoke(granted)
            recordAudioResult = null
        }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(QrScanBridge(this@MainActivity, this), "ZyncNative")
            addJavascriptInterface(
                CaptureSettingsBridge(this@MainActivity, this) { callback ->
                    recordAudioResult = callback
                    recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                "ZyncCapture",
            )
            backupAuthBridge = BackupAuthBridge(this) { intent -> googleDriveSignIn.launch(intent) }
            addJavascriptInterface(backupAuthBridge, "ZyncBackup")
        }
        setContentView(webView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        val app = application as ZyncApp
        BackupScheduler.schedulePeriodic(this)
        // Force the lazy `remoteAccess` manager into existence (and wired into
        // `pairingService.remoteAccess`, see ZyncApp) up front, so the settings view's
        // `/remote/*` routes are functional as soon as the WebView loads, not only after
        // whatever first touches `app.remoteAccess` on some other path.
        app.remoteAccess
        lifecycleScope.launch(Dispatchers.IO) {
            RestoreManager(this@MainActivity, app.backupSettings).restoreIfRequested()
            val port = app.ensureServerStarted()
            withContext(Dispatchers.Main) {
                webView.loadUrl("http://127.0.0.1:$port/?token=${app.serverToken}")
            }
        }
    }
}
