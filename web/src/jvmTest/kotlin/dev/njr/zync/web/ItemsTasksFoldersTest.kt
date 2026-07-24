package dev.njr.zync.web

import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.FileArea
import dev.njr.zync.web.content.NodeType
import dev.njr.zync.web.content.ProjectState
import dev.njr.zync.web.content.RecordingEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Items/Tasks/Folders derivation (spec 2026-07-24): type is a pure function of tree
 * location + whether a node holds children — nothing is stored as `kind` for content.
 */
class ItemsTasksFoldersTest {
    private val store = dev.njr.zync.core.state.InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    @Test
    fun typeIsDerivedFromLocationAndChildren() {
        // A fresh top-level node is an Inbox Item.
        val item = commands.createTask("triage me")
        assertEquals(NodeType.INBOX_ITEM, read.typeOf(item))

        // Moved into the Projects tree, the same node is a (single-action) Task.
        commands.move(item, WellKnownNodes.PROJECTS_ROOT)
        assertEquals(NodeType.TASK, read.typeOf(item))

        // Give it a child → it becomes a Project (folder); the child is a Task.
        val child = commands.addSubtask(item, "step one")
        assertEquals(NodeType.PROJECT, read.typeOf(item))
        assertEquals(NodeType.TASK, read.typeOf(child))

        // Reference tree: leaf = reference item, with a child = reference folder.
        val refItem = commands.createTask("a receipt")
        commands.move(refItem, WellKnownNodes.REFERENCE_ROOT)
        assertEquals(NodeType.REFERENCE_ITEM, read.typeOf(refItem))
        commands.addSubtask(refItem, "page 2")
        assertEquals(NodeType.REFERENCE_FOLDER, read.typeOf(refItem))
    }

    @Test
    fun projectStateFollowsItsTasks() {
        val project = commands.createTask("launch")
        commands.move(project, WellKnownNodes.PROJECTS_ROOT)
        val a = commands.addSubtask(project, "draft")
        val b = commands.addSubtask(project, "review")

        assertEquals(ProjectState.ACTIVE, read.projectState(project))

        commands.complete(a)
        assertEquals(ProjectState.ACTIVE, read.projectState(project)) // b still open

        commands.complete(b) // all leaves closed → done
        assertEquals(ProjectState.DONE, read.projectState(project))

        commands.trash(b) // DROPPED still counts as closed
        assertEquals(ProjectState.DONE, read.projectState(project))
    }

    /** Every File operation from the picker, verified through move() + the derived type. */
    @Test
    fun fileOperationsProduceExpectedTypes() {
        // Move to Projects root → the item becomes a Task.
        val item = commands.createTask("call the plumber")
        commands.move(item, WellKnownNodes.PROJECTS_ROOT)
        assertEquals(NodeType.TASK, read.typeOf(item))

        // Build a project folder (a task with a child) and file another item UNDER it → subtask Task.
        val project = commands.createTask("Kitchen remodel")
        commands.move(project, WellKnownNodes.PROJECTS_ROOT)
        commands.addSubtask(project, "measure counters")
        assertEquals(NodeType.PROJECT, read.typeOf(project))

        val filed = commands.createTask("order tiles")
        commands.move(filed, project) // file under the project folder
        assertEquals(NodeType.TASK, read.typeOf(filed))
        assertEquals(project.toString(), read.node(filed)!!.parent.toString())

        // Promote a plain Task to a Project by giving it a child.
        assertEquals(NodeType.TASK, read.typeOf(item))
        commands.addSubtask(item, "look up the number")
        assertEquals(NodeType.PROJECT, read.typeOf(item))

        // File an Inbox item into a Reference folder, then RE-file it to a different folder.
        val folderA = commands.createTask("Taxes")
        commands.move(folderA, WellKnownNodes.REFERENCE_ROOT)
        commands.addSubtask(folderA, "W-2") // makes A a reference folder
        val folderB = commands.createTask("Manuals")
        commands.move(folderB, WellKnownNodes.REFERENCE_ROOT)
        commands.addSubtask(folderB, "fridge")

        val receipt = commands.createTask("receipt.pdf")
        commands.move(receipt, folderA)
        assertEquals(NodeType.REFERENCE_ITEM, read.typeOf(receipt))
        assertEquals(folderA.toString(), read.node(receipt)!!.parent.toString())

        commands.move(receipt, folderB) // re-file to a new reference folder
        assertEquals(NodeType.REFERENCE_ITEM, read.typeOf(receipt))
        assertEquals(folderB.toString(), read.node(receipt)!!.parent.toString())

        // The picker offers the right destinations per tree.
        val projPaths = read.fileCandidates(FileArea.PROJECTS, receipt).map { it.path }
        assertTrue(projPaths.any { it == "Kitchen remodel" }, "projects candidates: $projPaths")
        assertTrue(projPaths.none { it.startsWith("Taxes") }, "reference nodes must not appear in Projects: $projPaths")
        val refPaths = read.fileCandidates(FileArea.REFERENCE, item).map { it.path }
        assertTrue(refPaths.any { it == "Taxes" } && refPaths.any { it == "Manuals" }, "reference candidates: $refPaths")
    }

    /** The tag editor: add turns a word into a tag; remove drops it (mergeable per-label ops). */
    @Test
    fun freeTagsAddAndRemove() {
        val t = commands.createTask("prep slides")
        commands.addFreeTag(t, "urgent")
        commands.addFreeTag(t, "q3")
        assertEquals(listOf("q3", "urgent"), read.node(t)!!.freeTags) // sorted

        commands.removeFreeTag(t, "urgent")
        assertEquals(listOf("q3"), read.node(t)!!.freeTags)
    }
}
