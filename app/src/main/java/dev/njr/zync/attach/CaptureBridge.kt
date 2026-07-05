package dev.njr.zync.attach

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface
import dev.njr.zync.capture.DocScanActivity
import dev.njr.zync.capture.DocUploadActivity
import dev.njr.zync.capture.VoiceCaptureActivity

class CaptureBridge(private val activity: Activity) {
    @JavascriptInterface
    fun startVoiceNote() {
        activity.startActivity(Intent(activity, VoiceCaptureActivity::class.java))
    }

    @JavascriptInterface
    fun startDocumentScan() {
        activity.startActivity(Intent(activity, DocScanActivity::class.java))
    }

    @JavascriptInterface
    fun startDocumentUpload() {
        activity.startActivity(Intent(activity, DocUploadActivity::class.java))
    }
}
