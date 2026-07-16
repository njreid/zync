package dev.njr.zync.home

import dev.njr.zync.web.content.NodeView
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.id.Ulid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The agenda builder: all-day split, free countdown, soon-highlight, NOW marker. */
class AgendaModelTest {
    private fun task(title: String): NodeView {
        val id = Ulid.generate(Clock { 1L }, Random(title.hashCode()))
        return NodeView(id, "task", title, null, "ACTIVE", null, null, null, null, emptySet(), true)
    }

    private fun event(title: String, begin: Long, end: Long, allDay: Boolean = false) =
        CalEvent(title, begin, end, CalEvent.Profile.HOME, "cal", allDay = allDay)

    private val m = 60_000L
    private val tasks = listOf(task("a"), task("b"), task("c"), task("d"))

    @Test
    fun splitsAllDayCountsFreeTimeAndFlagsSoonStarts() {
        val view = buildAgenda(
            events = listOf(
                event("Holiday", 0, 24 * 60 * m, allDay = true),
                event("Gym", begin = 10 * m, end = 60 * m),
                event("Soon", begin = 103 * m, end = 130 * m),
                event("Later", begin = 200 * m, end = 260 * m),
            ),
            now = 100 * m,
            suggestions = tasks,
        )
        assertEquals(listOf("Holiday"), view.allDay.map { it.title })
        assertTrue(view.free)
        assertEquals(3, view.freeMinutes)
        assertEquals(3, view.suggestions.size)
        val soon = view.rows.filterIsInstance<AgendaRow.Event>().first { it.event.title == "Soon" }
        assertTrue("starting within 5 min → inverted highlight", soon.startingSoon)
        val later = view.rows.filterIsInstance<AgendaRow.Event>().first { it.event.title == "Later" }
        assertTrue(!later.startingSoon)
        // Gym is past; NOW sits before Soon: [Event(Gym), Now, Event(Soon), Event(Later)]
        assertEquals(listOf("Event", "Now", "Event", "Event"), view.rows.map { it::class.simpleName })
    }

    @Test
    fun busyNowMeansNoFreeHeaderAndNoSuggestions() {
        val view = buildAgenda(
            events = listOf(
                event("Standup", begin = 90 * m, end = 110 * m), // in progress at now=100
                event("Review", begin = 130 * m, end = 160 * m),
            ),
            now = 100 * m,
            suggestions = tasks,
        )
        assertTrue(!view.free)
        assertNull(view.freeMinutes)
        assertTrue(view.suggestions.isEmpty())
    }

    @Test
    fun emptyDayIsFreeWithoutCountdown() {
        val view = buildAgenda(emptyList(), 100 * m, tasks)
        assertTrue(view.free)
        assertNull(view.freeMinutes)
        assertEquals(3, view.suggestions.size)
        assertEquals(listOf("Now"), view.rows.map { it::class.simpleName })
    }

    @Test
    fun duplicateNotificationEventsAreDeduped() {
        val fromCal = event("1:1 with Priya", 200 * m, 230 * m)
        val fromNotif = fromCal.copy(fromNotification = true, calendarName = "com.example.app")
        val view = buildAgenda(listOf(fromCal, fromNotif), 100 * m, emptyList())
        assertEquals(1, view.rows.count { it is AgendaRow.Event })
    }
}
