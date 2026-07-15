package dev.njr.zync.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The action bar's app slots build the right role-based selector intents (spec L1). */
@RunWith(RobolectricTestRunner::class)
class LauncherIntentsTest {
    @Test
    fun appSlotsTargetTheRoleDefaults() {
        val messages = LauncherIntents.messages()
        assertEquals(Intent.ACTION_MAIN, messages.action)
        assertTrue(messages.selector!!.hasCategory(Intent.CATEGORY_APP_MESSAGING))

        val calendar = LauncherIntents.calendar()
        assertTrue(calendar.selector!!.hasCategory(Intent.CATEGORY_APP_CALENDAR))

        val phone = LauncherIntents.phone()
        assertEquals(Intent.ACTION_DIAL, phone.action)

        for (intent in listOf(messages, calendar, phone)) {
            assertTrue("bar launches must not stack on zync's task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }
}
