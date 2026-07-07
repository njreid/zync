package dev.njr.zync.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that turns two hardware-key gestures into quick capture,
 * from anywhere on the device (the app need not be foreground):
 *
 *  - double-press **Volume Up**   → record a voice note into the Inbox
 *  - double-press **Volume Down**  → scan a document into the Inbox
 *
 * The user must enable this under Settings → Accessibility → zync (there's a
 * deep-link button in the app's Settings view; see [CaptureSettingsBridge]).
 * Filtering key events requires `canRequestFilterKeyEvents` + the
 * `flagRequestFilterKeyEvents` flag in `res/xml/capture_accessibility_service`.
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
                KeyEvent.KEYCODE_VOLUME_UP ->
                    if (detector.onKeyDown(event.keyCode, event.eventTime)) {
                        launch(VoiceCaptureActivity::class.java)
                    }
                KeyEvent.KEYCODE_VOLUME_DOWN ->
                    if (detector.onKeyDown(event.keyCode, event.eventTime)) {
                        launch(DocScanActivity::class.java)
                    }
            }
        }
        return false // never swallow volume keys
    }

    private fun launch(activity: Class<*>) {
        startActivity(
            Intent(this, activity).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        detector.reset()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* key events only */ }

    override fun onInterrupt() { detector.reset() }
}
