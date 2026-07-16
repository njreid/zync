package dev.njr.zync.home

import dev.njr.zync.web.content.NodeView
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.id.Ulid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The agenda builder: ordering, the NOW marker, and gap-fill suggestion rules. */
class AgendaModelTest {
    private fun task(title: String): NodeView {
        val id = Ulid.generate(Clock { 1L }, Random(title.hashCode()))
        return NodeView(id, "task", title, null, "ACTIVE", null, null, null, null, emptySet(), true)
    }

    private fun event(title: String, begin: Long, end: Long, work: Boolean = false) =
        CalEvent(title, begin, end, if (work) CalEvent.Profile.WORK else CalEvent.Profile.HOME, "cal")

    private val m = 60_000L
    private val tasks = listOf(task("a"), task("b"), task("c"), task("d"))

    @Test
    fun ordersEventsInsertsNowAndFillsTheGap() {
        val rows = buildAgenda(
            events = listOf(
                event("Lunch", begin = 200 * m, end = 260 * m),
                event("Gym", begin = 10 * m, end = 60 * m),
                event("Review", begin = 120 * m, end = 150 * m, work = true),
            ),
            now = 100 * m,
            suggestions = tasks,
        )
        val kinds = rows.map { it::class.simpleName }
        assertEquals(listOf("Event", "Now", "Gap", "Event", "Event"), kinds)
        val gap = rows[2] as AgendaRow.Gap
        assertEquals(20, gap.minutes)
        assertEquals("Review", gap.nextTitle)
        assertEquals("at most three suggestions", 3, gap.tasks.size)
        assertTrue((rows[0] as AgendaRow.Event).past)
    }

    @Test
    fun noGapSuggestionsWhileAnEventIsInProgress() {
        val rows = buildAgenda(
            events = listOf(
                event("Standup", begin = 90 * m, end = 110 * m), // in progress at now=100
                event("Review", begin = 130 * m, end = 160 * m),
            ),
            now = 100 * m,
            suggestions = tasks,
        )
        assertTrue("mid-meeting is not free time", rows.none { it is AgendaRow.Gap })
        assertTrue(rows.any { it is AgendaRow.Now })
    }

    @Test
    fun tinyGapsAndEmptySuggestionsProduceNoBlock() {
        val short = buildAgenda(listOf(event("Soon", 105 * m, 130 * m)), now = 100 * m, suggestions = tasks)
        assertTrue(short.none { it is AgendaRow.Gap })

        val none = buildAgenda(listOf(event("Later", 200 * m, 230 * m)), now = 100 * m, suggestions = emptyList())
        assertTrue(none.none { it is AgendaRow.Gap })
    }

    @Test
    fun emptyOrFinishedDayEndsWithNow() {
        assertEquals(listOf("Now"), buildAgenda(emptyList(), 100 * m, tasks).map { it::class.simpleName })
        val evening = buildAgenda(listOf(event("Gym", 10 * m, 60 * m)), now = 100 * m, suggestions = tasks)
        assertEquals(listOf("Event", "Now"), evening.map { it::class.simpleName })
    }
}
