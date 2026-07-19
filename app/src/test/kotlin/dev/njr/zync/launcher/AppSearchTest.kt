package dev.njr.zync.launcher

import android.app.SearchManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The swipe-left search surface's model (launcher spec L3). */
@RunWith(RobolectricTestRunner::class)
class AppSearchTest {
    private val apps = listOf(
        AppEntry("Calendar", "com.example.cal", "com.example.cal.Main"),
        AppEntry("Camera", "com.example.cam", "com.example.cam.Main"),
        AppEntry("Notes", "com.example.notes", "com.example.notes.Main"),
    )

    @Test
    fun filterIsCaseInsensitiveSubstringAndBlankMeansEverything() {
        assertEquals(apps, AppSearch.filter(apps, ""))
        assertEquals(listOf("Calendar", "Camera"), AppSearch.filter(apps, "ca").map { it.label })
        assertEquals(listOf("Notes"), AppSearch.filter(apps, "NOTE").map { it.label })
        assertTrue(AppSearch.filter(apps, "zzz").isEmpty())
    }

    @Test
    fun launchAndWebSearchIntentsAreWellFormed() {
        val launch = apps[0].launchIntent()
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertEquals("com.example.cal.Main", launch.component!!.className)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val web = AppSearch.webSearch("gtd weekly review")
        assertEquals(Intent.ACTION_WEB_SEARCH, web.action)
        assertEquals("gtd weekly review", web.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun initialsMatchAndRankFirst() {
        val apps = listOf(
            AppEntry("Recorder", "r", "R"),
            AppEntry("Google Calendar", "gc", "GC"),
            AppEntry("Logcat Viewer", "lv", "LV"),
        )
        val hits = AppSearch.filter(apps, "gc")
        assertEquals("Google Calendar", hits.first().label)
    }
}
