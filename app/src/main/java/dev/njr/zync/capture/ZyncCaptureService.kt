package dev.njr.zync.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.njr.zync.MainActivity

/**
 * Accessibility service that turns two hardware-key gestures into quick capture,
 * from anywhere on the device (the app need not be foreground) — the volume-key
 * analogue of the power-button double-press camera shortcut:
 *
 *  - double-press **Volume Down** → the native capture screen (voice-first)
 *  - double-press **Volume Up**   → record a raw voice note straight into the Inbox
 *
 * The user must enable this under Settings → Accessibility → zync (the capture
 * screen shows an enable row until they do; enabling an accessibility service
 * cannot be done programmatically). Filtering key events requires
 * `canRequestFilterKeyEvents` + the `flagRequestFilterKeyEvents` flag in
 * `res/xml/capture_accessibility_service`.
 *
 * We do NOT consume the volume events ([onKeyEvent] returns false), so normal
 * volume adjustment keeps working — the double-press just additionally launches
 * capture. Double-press recognition lives in the pure [DoublePressDetector].
 */
class ZyncCaptureService : AccessibilityService() {

    private val detector = DoublePressDetector()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only the initial press counts. Holding a volume key emits auto-repeat
        // ACTION_DOWN events (repeatCount > 0); feeding those to the detector
        // would let a single held key satisfy the double-press window and
        // spuriously launch capture.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN ->
                    if (detector.onKeyDown(event.keyCode, event.eventTime)) {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(MainActivity.EXTRA_OPEN_CAPTURE, true),
                        )
                    }
                KeyEvent.KEYCODE_VOLUME_UP ->
                    if (detector.onKeyDown(event.keyCode, event.eventTime)) {
                        startActivity(
                            Intent(this, VoiceCaptureActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                    }
            }
        }
        return false // never swallow volume keys
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        detector.reset()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* key events only */ }

    override fun onInterrupt() { detector.reset() }

    companion object {
        /** Whether the user has enabled this service under Settings → Accessibility. */
        fun isEnabled(context: Context): Boolean {
            val on = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (on != 1) return false
            val expected = ComponentName(context, ZyncCaptureService::class.java).flattenToString()
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(services)
            return splitter.any { it.equals(expected, ignoreCase = true) }
        }
    }
}
