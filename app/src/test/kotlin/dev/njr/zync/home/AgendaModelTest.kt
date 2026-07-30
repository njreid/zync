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
        return NodeView(
            id = id, kind = "task", title = title, notes = null, status = "ACTIVE",
            deferUntil = null, dueDate = null, person = null, ocrStatus = null, ocrBlobHash = null,
            summary = null, parent = null, tags = emptySet(), alive = true,
        )
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
    fun lookaheadBucketsByDayAndDropsEmptyOnes() {
        val day = 24 * 60 * m
        val days = listOf(
            Triple("Fri", day, 2 * day),
            Triple("Sat", 2 * day, 3 * day),
        )
        val sections = upcomingDays(
            listOf(
                event("Brunch", day + 600 * m, day + 660 * m),
                event("Trip", day, 2 * day, allDay = true),
                event("Early", day + 60 * m, day + 90 * m),
                // Saturday has nothing → no section
            ),
            days,
        )
        assertEquals(listOf("Fri"), sections.map { it.label })
        assertEquals(listOf("Trip"), sections[0].allDay.map { it.title })
        assertEquals(listOf("Early", "Brunch"), sections[0].timed.map { it.title })
    }

    @Test
    fun duplicateNotificationEventsAreDeduped() {
        val fromCal = event("1:1 with Priya", 200 * m, 230 * m)
        val fromNotif = fromCal.copy(fromNotification = true, calendarName = "com.example.app")
        val view = buildAgenda(listOf(fromCal, fromNotif), 100 * m, emptyList())
        assertEquals(1, view.rows.count { it is AgendaRow.Event })
    }

    private fun localMidnight(date: java.time.LocalDate, zone: java.time.ZoneId) =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun allDayFromAnotherTimezoneSnapsToLocalDayAndBucketsOnce() {
        // A server-pushed all-day event for 2026-07-20 encoded at UTC midnight, seen on a device
        // in Berlin (UTC+2 in July): its raw [begin, end) pokes into the 21st, so before the snap
        // it matched both day windows (today AND tomorrow). After the snap it lands on ONE day.
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        val date = java.time.LocalDate.of(2026, 7, 20)
        val utcMidnight = localMidnight(date, java.time.ZoneId.of("UTC"))
        val raw = event("Home", utcMidnight, utcMidnight + 24 * 60 * m, allDay = true)

        val snapped = dev.njr.zync.home.CalendarSource.normalizeAllDay(raw, java.util.TimeZone.getTimeZone(berlin))
        val mid20 = localMidnight(date, berlin)
        val mid21 = localMidnight(date.plusDays(1), berlin)
        assertEquals(mid20, snapped.beginMillis)
        assertEquals(mid21, snapped.endMillis)

        val days = listOf(
            Triple("20th", mid20, mid21),
            Triple("21st", mid21, localMidnight(date.plusDays(2), berlin)),
        )
        // Raw event bucketed both days; snapped event lands only on the 20th.
        assertEquals(listOf("20th", "21st"), upcomingDays(listOf(raw), days).map { it.label })
        assertEquals(listOf("20th"), upcomingDays(listOf(snapped), days).map { it.label })
    }
}
