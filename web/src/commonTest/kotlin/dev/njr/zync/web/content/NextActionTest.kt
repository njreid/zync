package dev.njr.zync.web.content

import dev.njr.zync.core.content.Size
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Next Action algorithm (GTD triage §5, build #3): top loose action + one row per
 * project, context-scoped, ordered rank + dueDate/size (RESOLVED Q3).
 */
class NextActionTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    private fun titles(rows: List<NextRow>) = rows.map { it.action.title }

    @Test
    fun looseTopThenOnePerProject() {
        commands.createTask("loose-a")
        commands.createTask("loose-b")
        val projA = commands.createProject("Proj A")
        commands.createTask("a1", projA)
        commands.createTask("a2", projA)
        val projB = commands.createProject("Proj B")
        commands.createTask("b1", projB)

        val rows = read.nextActions(context = null)
        // one loose (the top) + one row per project
        assertEquals(3, rows.size)
        assertEquals("loose-a", rows[0].action.title)
        assertEquals(null, rows[0].project)
        val projectRows = rows.drop(1)
        assertTrue(projectRows.all { it.project != null })
        assertEquals(setOf("a1", "b1"), projectRows.map { it.action.title }.toSet())
    }

    @Test
    fun excludesWaitingDoneDroppedDeferredAndDelegated() {
        val done = commands.createTask("done"); commands.complete(done)
        val dropped = commands.createTask("dropped"); commands.trash(dropped)
        val waiting = commands.createTask("waiting"); commands.setPerson(waiting, "Sam")
        val deferred = commands.createTask("deferred"); commands.defer(deferred, 1000)
        commands.createTask("visible")

        assertEquals(listOf("visible"), titles(read.nextActions(context = null, now = 500)))
        // once now passes the defer time, the deferred one is completable (top by ULID)
        assertTrue(titles(read.nextActions(context = null, now = 2000)).contains("deferred"))
    }

    @Test
    fun excludesInboxItems() {
        val inbox = commands.createProject("Inbox")
        commands.createTask("triage me", inbox)
        commands.createTask("loose")
        val rows = read.nextActions(context = null, inbox = inbox)
        assertEquals(listOf("loose"), titles(rows))
    }

    @Test
    fun contextScoping() {
        val ctx = commands.createContext("@home")
        // "untagged" created first → earlier ULID → it is the top loose when unfiltered.
        commands.createTask("untagged")
        val tagged = commands.createTask("home task"); commands.addTag(tagged, ctx)

        // Context C excludes the untagged loose task, leaving the tagged one.
        assertEquals(listOf("home task"), titles(read.nextActions(context = ctx)))
        // No context → the top loose overall is the untagged one (so it is in the pool).
        assertEquals(listOf("untagged"), titles(read.nextActions(context = null)))
    }

    @Test
    fun dueDateBumpsAheadOfRank() {
        val proj = commands.createProject("P")
        val hi = commands.createTask("hi-rank-no-due", proj)
        val lo = commands.createTask("lo-rank-due", proj)
        // Give hi a better (earlier) rank, lo a worse rank but a due date.
        commands.setRank(hi, "a")
        commands.setRank(lo, "z")
        commands.setDueDate(lo, 1_000_000L)

        val row = read.nextActions(context = null).first { it.project != null }
        assertEquals("lo-rank-due", row.action.title)
    }

    @Test
    fun sizeDegradesToRankWhenAbsentThenBumpsWhenSet() {
        val proj = commands.createProject("P")
        val a = commands.createTask("a", proj)
        val b = commands.createTask("b", proj)
        commands.setRank(a, "a") // better rank
        commands.setRank(b, "z")

        // No sizes → pure rank: a wins.
        assertEquals("a", read.nextActions(context = null).first { it.project != null }.action.title)

        // Make b small → size bump beats rank.
        commands.setSize(b, Size.S)
        assertEquals("b", read.nextActions(context = null).first { it.project != null }.action.title)
    }

    @Test
    fun projectRowsOrderedByProjectRank() {
        val p1 = commands.createProject("First"); commands.createTask("f1", p1)
        val p2 = commands.createProject("Second"); commands.createTask("s1", p2)
        commands.setRank(p2, "a") // p2 sorts before p1
        commands.setRank(p1, "z")

        val projectRows = read.nextActions(context = null).filter { it.project != null }
        assertEquals(listOf("Second", "First"), projectRows.map { it.project!!.title })
    }
}
