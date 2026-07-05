package dev.njr.zync.capture

/**
 * Detects a "double press" of a hardware key: two down-events of the same key
 * code within [windowMs] of each other. Kept a pure, synchronous state machine
 * (no Android types) so the gesture logic is unit-testable; the
 * [ZyncCaptureService] accessibility service feeds it `(keyCode, eventTimeMs)`
 * and fires the capture when [onKeyDown] returns true.
 *
 * Each key code is tracked independently, so volume-up and volume-down
 * double-presses don't interfere. Completing a double-press resets that key's
 * state, so a third rapid press begins a fresh sequence rather than
 * double-firing.
 */
class DoublePressDetector(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val lastDownAt = HashMap<Int, Long>()

    /**
     * Feed a key-down event. Returns true exactly on the press that completes a
     * double-press (the second of two presses within [windowMs]).
     */
    fun onKeyDown(keyCode: Int, atMs: Long): Boolean {
        val previous = lastDownAt[keyCode]
        if (previous != null && atMs - previous in 0..windowMs) {
            lastDownAt.remove(keyCode)
            return true
        }
        lastDownAt[keyCode] = atMs
        return false
    }

    /** Forget any in-progress sequences (e.g. when the service disconnects). */
    fun reset() = lastDownAt.clear()

    companion object {
        /** Max gap between the two presses. ~400 ms matches a comfortable double-tap. */
        const val DEFAULT_WINDOW_MS = 400L
    }
}
