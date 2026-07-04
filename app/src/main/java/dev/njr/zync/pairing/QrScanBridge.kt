package dev.njr.zync.pairing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Bridges the settings web view's "Pair a browser" button to Google Play Services' on-device
 * code-scanner (`play-services-code-scanner`): [GmsBarcodeScanning] launches its own full-screen
 * scanning UI as a separate activity and hands back only the decoded string — no `CAMERA`
 * permission is needed in the manifest, Play Services owns that surface entirely.
 *
 * Registered on the WebView as `window.ZyncNative` (see `MainActivity`). [scanPairingQr] is
 * fire-and-forget from JS's point of view: it kicks off the native scan and, once Play Services
 * calls back (success, failure, or user-cancel), the result is delivered back into the page by
 * evaluating a JS callback the page itself defines (`window.__zyncQrScanResult`), since
 * `@JavascriptInterface` methods can't return a value asynchronously to a `Promise` directly.
 *
 * This bridge is loopback/in-WebView only in the sense that it only exists inside this app's own
 * WebView — there is no way for a remote (LAN) page to reach it, since it's Java, not HTTP.
 */
class QrScanBridge(private val context: Context, private val webView: WebView) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun scanPairingQr() {
        mainHandler.post {
            GmsBarcodeScanning.getClient(context).startScan()
                .addOnSuccessListener { barcode -> deliver(barcode.rawValue, null) }
                .addOnCanceledListener { deliver(null, "cancelled") }
                .addOnFailureListener { e -> deliver(null, e.message ?: "scan failed") }
        }
    }

    private fun deliver(payload: String?, error: String?) {
        mainHandler.post {
            val js = "window.__zyncQrScanResult && window.__zyncQrScanResult(" +
                "${jsStringOrNull(payload)}, ${jsStringOrNull(error)});"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun jsStringOrNull(s: String?): String {
        if (s == null) return "null"
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "\"$escaped\""
    }
}
