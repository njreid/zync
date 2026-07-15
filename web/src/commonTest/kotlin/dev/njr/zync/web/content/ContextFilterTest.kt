package dev.njr.zync.web.content

import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context view (launcher spec L4): a flat, tree-wide next-actions list for one
 * context — tasks only, tag-matched, minus DONE/DROPPED/deferred-out.
 */
class ContextFilterTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
    private val read = ContentReadModel(store)

    @Test
    fun contextTasksAreFlatTaggedActiveTasksAcrossTheTree() {
        val errands = commands.createContext("@errands")
        val other = commands.createContext("@computer")

        val project = commands.createProject("House")
        val tagged = commands.createTask("Buy paint", parent = project) // nested — still surfaces
        val done = commands.createTask("Return ladder")
        val deferred = commands.createTask("Renew licence")
        val untagged = commands.createTask("Read a book")
        listOf(tagged, done, deferred).forEach { commands.addTag(it, errands) }
        commands.addTag(project, errands) // projects never appear in the flat view
        commands.complete(done)
        commands.defer(deferred, untilMillis = 2_000)

        val now = 1_000L
        val view = read.contextTasks(errands, now)
        assertEquals(listOf(tagged), view.map { it.id }, "only the live, active, tagged TASK")
        assertTrue(read.contextTasks(other, now).isEmpty())

        // Deferred task surfaces once its time arrives.
        assertEquals(listOf(tagged, deferred).sortedBy { it.toString() }.size, read.contextTasks(errands, 3_000).size)
    }
}
