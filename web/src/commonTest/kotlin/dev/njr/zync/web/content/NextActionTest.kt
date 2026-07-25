package dev.njr.zync.web.content

import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Next Action algorithm (GTD §5) under the Items/Tasks/Folders model: single-action Tasks
 * (leaves under PROJECTS_ROOT) each stand alone, plus one row per project folder. Inbox Items
 * (top-level leaves) never appear. Ordered rank + dueDate/size.
 */
class NextActionTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    private fun titles(rows: List<NextRow>) = rows.map { it.action.title }

    /** A single-action Task: a leaf filed directly under the Projects root. */
    private fun single(title: String): Ulid =
        commands.createTask(title).also { commands.move(it, WellKnownNodes.PROJECTS_ROOT) }

    /** A project folder: a node under Projects that gains child tasks. */
    private fun project(title: String): Ulid =
        commands.createTask(title).also { commands.move(it, WellKnownNodes.PROJECTS_ROOT) }

    @Test
    fun singleActionsEachRowPlusOnePerProject() {
        single("single-a")
        single("single-b")
        val projA = project("Proj A"); commands.createTask("a1", projA); commands.createTask("a2", projA)
        val projB = project("Proj B"); commands.createTask("b1", projB)

        val rows = read.nextActions(context = null)
        val singles = rows.filter { it.project == null }.map { it.action.title }
        val projectRows = rows.filter { it.project != null }
        assertEquals(setOf("single-a", "single-b"), singles.toSet())
        assertEquals(setOf("a1", "b1"), projectRows.map { it.action.title }.toSet())
    }

    @Test
    fun excludesWaitingDoneDroppedDeferredAndDelegated() {
        val done = single("done"); commands.complete(done)
        val dropped = single("dropped"); commands.trash(dropped)
        val waiting = single("waiting"); commands.setPerson(waiting, "Sam")
        val deferred = single("deferred"); commands.defer(deferred, 1000)
        single("visible")

        assertEquals(listOf("visible"), titles(read.nextActions(context = null, now = 500)))
        assertTrue(titles(read.nextActions(context = null, now = 2000)).contains("deferred"))
    }

    @Test
    fun excludesInboxItems() {
        commands.createTask("triage me") // top-level leaf = Inbox Item → never in Next
        single("action")
        assertEquals(listOf("action"), titles(read.nextActions(context = null)))
    }

    @Test
    fun contextScoping() {
        val ctx = commands.createContext("@home")
        single("untagged")
        val tagged = single("home task"); commands.addTag(tagged, ctx)

        assertEquals(listOf("home task"), titles(read.nextActions(context = ctx)))
        assertEquals(setOf("untagged", "home task"), titles(read.nextActions(context = null)).toSet())
    }

    @Test
    fun dueDateBumpsAheadOfRank() {
        val proj = project("P")
        val hi = commands.createTask("hi-rank-no-due", proj)
        val lo = commands.createTask("lo-rank-due", proj)
        commands.setRank(hi, "a")
        commands.setRank(lo, "z")
        commands.setDueDate(lo, 1_000_000L)

        val row = read.nextActions(context = null).first { it.project != null }
        assertEquals("lo-rank-due", row.action.title)
    }

    @Test
    fun sizeDegradesToRankWhenAbsentThenBumpsWhenSet() {
        val proj = project("P")
        val a = commands.createTask("a", proj)
        val b = commands.createTask("b", proj)
        commands.setRank(a, "a")
        commands.setRank(b, "z")

        assertEquals("a", read.nextActions(context = null).first { it.project != null }.action.title)

        commands.setSize(b, Size.S)
        assertEquals("b", read.nextActions(context = null).first { it.project != null }.action.title)
    }

    @Test
    fun projectRowsOrderedByProjectRank() {
        val p1 = project("First"); commands.createTask("f1", p1)
        val p2 = project("Second"); commands.createTask("s1", p2)
        commands.setRank(p2, "a")
        commands.setRank(p1, "z")

        val projectRows = read.nextActions(context = null).filter { it.project != null }
        assertEquals(listOf("Second", "First"), projectRows.map { it.project!!.title })
    }
}
