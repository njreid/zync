package dev.njr.zync.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Launch-frequency ranking in the search surface (tier first, then usage, then label). */
@RunWith(RobolectricTestRunner::class)
class AppRankingTest {
    private val apps = listOf(
        AppEntry("Calendar", "com.example.cal", "com.example.cal.Main"),
        AppEntry("Camera", "com.example.cam", "com.example.cam.Main"),
        AppEntry("Notes", "com.example.notes", "com.example.notes.Main"),
    )

    @Test
    fun usageBreaksTiesWithinATier() {
        // Both "Calendar" and "Camera" are prefix hits for "ca"; Camera is launched more.
        val usage = mapOf(
            SearchHistory.usageKey("com.example.cam", "com.example.cam.Main", null) to 9L,
            SearchHistory.usageKey("com.example.cal", "com.example.cal.Main", null) to 2L,
        )
        assertEquals(
            listOf("Camera", "Calendar"),
            AppSearch.filter(apps, "ca", usage).map { it.label },
        )
    }

    @Test
    fun matchTierStillBeatsUsage() {
        // "Notes" is a prefix hit for "no" with 0 uses; "Piano" only matches mid-word
        // but has 100 uses — the prefix hit must still rank first.
        val apps = listOf(
            AppEntry("Piano", "com.example.piano", "com.example.piano.Main"),
            AppEntry("Notes", "com.example.notes", "com.example.notes.Main"),
        )
        val usage = mapOf(
            SearchHistory.usageKey("com.example.piano", "com.example.piano.Main", null) to 100L,
        )
        assertEquals(
            listOf("Notes", "Piano"),
            AppSearch.filter(apps, "no", usage).map { it.label },
        )
    }

    @Test
    fun blankQuerySortsByUsageThenLabel() {
        val usage = mapOf(
            SearchHistory.usageKey("com.example.notes", "com.example.notes.Main", null) to 5L,
        )
        // Notes leads on usage; Calendar and Camera tie at 0 and fall back to label order.
        assertEquals(
            listOf("Notes", "Calendar", "Camera"),
            AppSearch.filter(apps, "", usage).map { it.label },
        )
    }
}
