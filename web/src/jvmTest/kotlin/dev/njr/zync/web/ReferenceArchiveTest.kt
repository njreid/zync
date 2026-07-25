package dev.njr.zync.web

import dev.njr.zync.core.content.ReadingState
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.Location
import dev.njr.zync.web.content.NodeType
import dev.njr.zync.web.content.RecordingEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reference-folder markers, Reading, Task→Reference links, and Archive (reference-and-archive spec,
 * stage 1): all derived from the read model over an in-memory store — no HTTP.
 */
class ReferenceArchiveTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    private fun ids(ns: List<dev.njr.zync.web.content.NodeView>) = ns.map { it.id.toString() }

    /** Archiving a project takes it and its tasks out of every active surface, but not out of search. */
    @Test
    fun archivedProjectIsInertButSearchable() {
        val project = commands.createProject("Launch")
        commands.move(project, WellKnownNodes.PROJECTS_ROOT)
        val task = commands.addSubtask(project, "ship the thing")
        commands.setDueDate(task, 1_000L)
        val ctx = commands.createContext("errand")
        commands.addTag(task, ctx)

        // Live: on Next / Today / context / active pools, and findable.
        assertTrue(ids(read.activeTasks()).contains(task.toString()))
        assertTrue(ids(read.dueTasks(Long.MAX_VALUE)).contains(task.toString()))
        assertTrue(ids(read.contextTasks(ctx)).contains(task.toString()))
        assertTrue(read.nextActions(context = null).any { it.action.id == task })
        assertTrue(read.projects().any { it.id == project })
        assertTrue(ids(read.search("ship")).contains(task.toString()))

        commands.archiveProject(project)

        // Inert: gone from every active surface; the project drops off the Projects list.
        assertFalse(ids(read.activeTasks()).contains(task.toString()))
        assertFalse(ids(read.dueTasks(Long.MAX_VALUE)).contains(task.toString()))
        assertFalse(ids(read.contextTasks(ctx)).contains(task.toString()))
        assertFalse(read.nextActions(context = null).any { it.action.id == task })
        assertFalse(read.projects().any { it.id == project })
        // ...but still searchable, and typed/located as archived.
        assertTrue(ids(read.search("ship")).contains(task.toString()))
        assertEquals(Location.ARCHIVE, read.locationOf(task))
        assertEquals(NodeType.ARCHIVED, read.typeOf(task))
        assertEquals(NodeType.ARCHIVED, read.typeOf(project))

        // Reversible: back under Projects and it goes live again.
        commands.unarchive(project)
        assertTrue(ids(read.activeTasks()).contains(task.toString()))
        assertEquals(Location.PROJECTS, read.locationOf(task))
    }

    /** The folder marker (not has-children) decides folder vs item; an empty marked node is a folder. */
    @Test
    fun folderMarkerMakesEmptyNodeAReferenceFolder() {
        val folder = commands.createFolder("Manuals", WellKnownNodes.REFERENCE_ROOT)
        assertEquals(NodeType.REFERENCE_FOLDER, read.typeOf(folder)) // empty, but marked
        assertEquals(Location.REFERENCE, read.locationOf(folder))

        val item = commands.createTask("fridge manual")
        commands.move(item, WellKnownNodes.REFERENCE_ROOT)
        assertEquals(NodeType.REFERENCE_ITEM, read.typeOf(item))

        // locationOf across the areas.
        val inboxItem = commands.createTask("triage me")
        assertEquals(Location.INBOX, read.locationOf(inboxItem))
        val filedTask = commands.createTask("do it"); commands.move(filedTask, WellKnownNodes.PROJECTS_ROOT)
        assertEquals(Location.PROJECTS, read.locationOf(filedTask))
    }

    /** Reading = reference items with readingState in {READING, FINISHED}; UNREAD and folders stay out. */
    @Test
    fun readingListMembership() {
        val folder = commands.createFolder("Articles", WellKnownNodes.REFERENCE_ROOT)
        val a = commands.createTask("Article A"); commands.move(a, WellKnownNodes.REFERENCE_ROOT)
        val b = commands.createTask("Article B"); commands.move(b, WellKnownNodes.REFERENCE_ROOT)
        val c = commands.createTask("Article C"); commands.move(c, WellKnownNodes.REFERENCE_ROOT)
        commands.setReadingState(a, ReadingState.READING)
        commands.setReadingState(b, ReadingState.FINISHED)
        // c stays UNREAD (the universal default)

        val reading = ids(read.reading())
        assertTrue(reading.contains(a.toString()) && reading.contains(b.toString()))
        assertFalse(reading.contains(c.toString()), "UNREAD stays out")
        assertFalse(reading.contains(folder.toString()), "folders are not readable items")
    }

    /** Task→Reference links are a mergeable per-URL set: add both, remove one. */
    @Test
    fun referenceLinksAddAndRemove() {
        val task = commands.createTask("call the bank")
        commands.move(task, WellKnownNodes.PROJECTS_ROOT)
        commands.addReferenceLink(task, "/Personal/Family/info")
        commands.addReferenceLink(task, "https://example.com/rates")

        assertEquals(listOf("/Personal/Family/info", "https://example.com/rates"), read.referenceLinks(task))
        assertEquals(read.referenceLinks(task), read.node(task)!!.referenceLinks) // also on the view

        commands.removeReferenceLink(task, "https://example.com/rates")
        assertEquals(listOf("/Personal/Family/info"), read.referenceLinks(task))
    }
}
