package dev.njr.zync.capture

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import android.media.MediaRecorder
import android.provider.Settings
import android.text.TextUtils
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.MainActivity
import dev.njr.zync.ZyncApp
import dev.njr.zync.data.AttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exposes a JS hook (`window.ZyncCapture.openAccessibilitySettings()`) so the
 * Settings web view can deep-link the user to Android's Accessibility settings,
 * where they enable the [ZyncCaptureService] that powers the volume-key capture
 * gestures. Enabling an accessibility service cannot be done programmatically —
 * the OS requires the user to toggle it — so this just opens the right screen.
 *
 * Registered on the WebView (see `ui/WebViewHost.createZyncWebView`) as `ZyncCapture`.
 */
class CaptureSettingsBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val requestRecordAudio: ((Boolean) -> Unit) -> Unit,
) {
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var recording = false

    @JavascriptInterface
    fun openAccessibilitySettings() {
        activity.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @JavascriptInterface
    fun isQuickCaptureEnabled(): Boolean = ZyncCaptureService.isEnabled(activity)

    @JavascriptInterface
    fun startVoiceNote() {
        activity.runOnUiThread {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                requestRecordAudio { granted ->
                    if (granted) {
                        startRecording()
                    } else {
                        toast("Microphone permission needed")
                        dispatch("zync-capture-discarded")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun saveVoiceNote() {
        activity.runOnUiThread { stopAndSave() }
    }

    @JavascriptInterface
    fun restartVoiceNote() {
        activity.runOnUiThread {
            stopAndDelete()
            startVoiceNote()
        }
    }

    @JavascriptInterface
    fun discardVoiceNote() {
        activity.runOnUiThread {
            stopAndDelete()
            toast("Discarded voice note")
            dispatch("zync-capture-discarded")
        }
    }

    @JavascriptInterface
    fun startDocumentScan() {
        activity.startActivity(Intent(activity, DocScanActivity::class.java))
    }

    @JavascriptInterface
    fun startDocumentUpload() {
        activity.startActivity(Intent(activity, DocUploadActivity::class.java))
    }

    private fun startRecording() {
        if (recording) return
        val file = File(activity.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        runCatching {
            recorder = MediaRecorder(activity).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            outFile = file
            recording = true
            toast("Recording voice note")
        }.onFailure { e ->
            recorder?.release()
            recorder = null
            outFile = null
            toast(e.message ?: "Could not start recording")
            dispatch("zync-capture-discarded")
        }
    }

    private fun stopAndSave() {
        if (!recording) {
            dispatch("zync-capture-discarded")
            return
        }
        recording = false
        val file = outFile
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        outFile = null
        if (file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            toast("Nothing recorded")
            dispatch("zync-capture-discarded")
            return
        }

        val app = activity.application as ZyncApp
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                app.captureToInbox("Voice note", AttachmentType.AUDIO, bytes, "m4a")
                file.delete()
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    toast("Added voice note to Inbox")
                    dispatch("zync-capture-saved")
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    toast(e.message ?: "Could not save voice note")
                    dispatch("zync-capture-discarded")
                }
            }
        }
    }

    private fun stopAndDelete() {
        val wasRecording = recording
        recording = false
        runCatching { if (wasRecording) recorder?.stop() }
        recorder?.release()
        recorder = null
        outFile?.delete()
        outFile = null
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun dispatch(name: String) {
        webView.post {
            webView.evaluateJavascript("window.dispatchEvent(new Event('$name'))", null)
        }
    }

}
