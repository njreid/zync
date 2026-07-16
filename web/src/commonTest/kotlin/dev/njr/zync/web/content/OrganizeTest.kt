package dev.njr.zync.web.content

import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Due dates, person, and the Today/move-target views (the organize vocabulary). */
class OrganizeTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
    private val read = ContentReadModel(store)

    @Test
    fun dueDateRoundTripsParsesAndClears() {
        assertEquals("2026-07-18", DueDates.format(DueDates.parse("2026-07-18")!!))
        assertEquals("2026-01-01", DueDates.format(DueDates.parse("2026-01-01")!!))
        assertEquals("1969-12-31", DueDates.format(DueDates.parse("1969-12-31")!!), "pre-epoch dates")
        assertNull(DueDates.parse("18/07/2026"))
        assertNull(DueDates.parse("2026-13-01"))
        assertNull(DueDates.parse(""))

        val t = commands.createTask("File taxes")
        commands.setDueDate(t, DueDates.parse("2026-07-18"))
        assertEquals("2026-07-18", DueDates.format(read.node(t)!!.dueDate!!))
        commands.setDueDate(t, null)
        assertNull(read.node(t)!!.dueDate, "cleared due date reads as absent")
    }

    @Test
    fun personSetsAndClearsWithoutJsonNullLeaking() {
        val t = commands.createTask("Chase contract")
        commands.setPerson(t, "Dana Kim")
        assertEquals("Dana Kim", read.node(t)!!.person)
        commands.setPerson(t, "  ")
        assertNull(read.node(t)!!.person, "blank clears; JsonNull must not read as the string \"null\"")
    }

    @Test
    fun dueTasksIsTheTodayView() {
        val overdue = commands.createTask("Overdue")
        val today = commands.createTask("Today")
        val nextWeek = commands.createTask("Next week")
        val done = commands.createTask("Done already")
        commands.setDueDate(overdue, DueDates.parse("2026-07-10"))
        commands.setDueDate(today, DueDates.parse("2026-07-16"))
        commands.setDueDate(nextWeek, DueDates.parse("2026-07-23"))
        commands.setDueDate(done, DueDates.parse("2026-07-16"))
        commands.complete(done)

        val endOfToday = DueDates.parse("2026-07-16")!!
        assertEquals(listOf(overdue, today), read.dueTasks(endOfToday).map { it.id }, "overdue first, done excluded")
    }

    @Test
    fun projectsAreTheMoveTargetsAndMoveFilesIntoTheTree() {
        val house = commands.createProject("House")
        val work = commands.createProject("Work")
        commands.trash(work)
        val task = commands.createTask("Buy paint")

        assertEquals(listOf(house), read.projects().map { it.id }, "trashed projects are not targets")

        commands.move(task, house)
        assertTrue(read.children(house).any { it.id == task }, "task filed under the project")
        assertTrue(read.children(null).none { it.id == task }, "gone from the root")
    }
}
