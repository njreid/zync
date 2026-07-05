package dev.njr.zync.capture

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface

/**
 * Exposes a JS hook (`window.ZyncCapture.openAccessibilitySettings()`) so the
 * Settings web view can deep-link the user to Android's Accessibility settings,
 * where they enable the [ZyncCaptureService] that powers the volume-key capture
 * gestures. Enabling an accessibility service cannot be done programmatically —
 * the OS requires the user to toggle it — so this just opens the right screen.
 *
 * Registered on the WebView in `MainActivity` as `ZyncCapture`. Java-only,
 * unreachable from any remote (LAN) page.
 */
class CaptureSettingsBridge(private val context: Context) {

    @JavascriptInterface
    fun openAccessibilitySettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
