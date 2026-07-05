package dev.njr.zync.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoublePressDetectorTest {

    private val VOL_UP = 24
    private val VOL_DOWN = 25

    @Test
    fun `single press does not fire`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L))
    }

    @Test
    fun `two presses within the window fire on the second`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L))
        assertTrue(d.onKeyDown(VOL_UP, 1300L))
    }

    @Test
    fun `two presses outside the window do not fire`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L))
        assertFalse(d.onKeyDown(VOL_UP, 1500L))
    }

    @Test
    fun `a late second press becomes the start of a new sequence`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L)) // first
        assertFalse(d.onKeyDown(VOL_UP, 2000L)) // too late -> new first
        assertTrue(d.onKeyDown(VOL_UP, 2200L))  // completes the double
    }

    @Test
    fun `different key codes are tracked independently`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L))
        assertFalse(d.onKeyDown(VOL_DOWN, 1100L)) // different key, still first
        assertTrue(d.onKeyDown(VOL_UP, 1200L))    // completes vol-up double
        assertTrue(d.onKeyDown(VOL_DOWN, 1300L))  // completes vol-down double
    }

    @Test
    fun `triple press fires once then resets`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L))
        assertTrue(d.onKeyDown(VOL_UP, 1200L))  // double fires
        assertFalse(d.onKeyDown(VOL_UP, 1400L)) // third starts a fresh sequence
    }

    @Test
    fun `reset clears in-progress sequence`() {
        val d = DoublePressDetector(windowMs = 400L)
        assertFalse(d.onKeyDown(VOL_UP, 1000L))
        d.reset()
        assertFalse(d.onKeyDown(VOL_UP, 1200L)) // would have been a double without reset
    }
}
