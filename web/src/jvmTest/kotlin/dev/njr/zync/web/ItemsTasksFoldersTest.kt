package dev.njr.zync.web

import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.NodeType
import dev.njr.zync.web.content.ProjectState
import dev.njr.zync.web.content.RecordingEmitter
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
