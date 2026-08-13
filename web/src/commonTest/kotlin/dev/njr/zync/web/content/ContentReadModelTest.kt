package dev.njr.zync.web.content

import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ContentReadModel.childrenIndex] must return, for every parent (including the root/null
 * parent), exactly what [ContentReadModel.children] returns for that same parent — it's a
 * compute-once replacement for calling [ContentReadModel.children] once per node while
 * rendering a tree, not a behavior change. This is the load-bearing property to preserve.
 */
class ContentReadModelTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    @Test
    fun childrenIndexMatchesChildrenForEveryParent() {
        // root
        //  ├── a (has its own child a1)
        //  │     └── a1
        //  └── b
        val root = commands.createProject("root")
        val a = commands.createTask("a", root)
        val b = commands.createTask("b", root)
        val a1 = commands.addSubtask(a, "a1")

        val index = read.childrenIndex()

        // Every parent that appears anywhere (including null/root, and leaves with no children)
        // must produce identical results to calling children(parent) directly.
        val parentsToCheck: List<Ulid?> = listOf(null, root, a, b, a1)
        for (parent in parentsToCheck) {
            assertEquals(
                read.children(parent).map { it.id },
                index[parent].orEmpty().map { it.id },
                "childrenIndex()[$parent] must match children($parent)",
            )
        }
    }

    @Test
    fun childrenIndexMatchesChildrenAfterCompleteAndTrashMutations() {
        // Completion/trashing change a node's status/aliveness, which children()'s filter
        // reacts to (via snapshots()) — childrenIndex() must track those same mutations
        // identically since both read from the same live snapshot pass.
        val root = commands.createProject("root")
        commands.createTask("kept", root)
        val done = commands.createTask("done", root)
        val dropped = commands.createTask("dropped", root)
        commands.complete(done)
        commands.trash(dropped)

        val index = read.childrenIndex()
        assertEquals(
            read.children(root).map { it.id },
            index[root].orEmpty().map { it.id },
            "childrenIndex() must reflect the same live/completed/trashed filtering as children()",
        )
    }
}
