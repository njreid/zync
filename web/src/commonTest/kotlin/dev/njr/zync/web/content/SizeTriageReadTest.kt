package dev.njr.zync.web.content

import dev.njr.zync.core.content.Size
import dev.njr.zync.core.state.InMemoryStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** size set/clear/reject, split → project, and the 4-level depth cap (GTD triage §4/§8). */
class SizeTriageReadTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    @Test
    fun setSizeSetsClearsAndRejects() {
        val t = commands.createTask("t")
        commands.setSize(t, Size.M)
        assertEquals("M", read.node(t)!!.size)
        commands.setSize(t, "XL") // invalid → clears
        assertNull(read.node(t)!!.size)
        commands.setSize(t, Size.L)
        commands.setSize(t, null) // explicit clear
        assertNull(read.node(t)!!.size)
    }

    @Test
    fun splitMakesParentAProject() {
        val t = commands.createTask("plan trip")
        val child = commands.split(t, "book flights")
        assertEquals("project", read.node(t)!!.kind)
        assertTrue(read.children(t).any { it.id == child })
    }

    @Test
    fun depthHelpersAndCap() {
        // Build a chain a > b > c > d (root child a = depth 1 … d = depth 4).
        val a = commands.createProject("a")
        val b = commands.createTask("b", a)
        val c = commands.createTask("c", b)
        val d = commands.createTask("d", c)
        assertEquals(1, read.depthOf(a))
        assertEquals(4, read.depthOf(d))
        assertEquals(3, read.subtreeHeight(a))

        // Moving a leaf under d (depth 4) would create depth 5 → rejected.
        val loose = commands.createTask("loose")
        assertTrue(read.moveWouldExceedDepth(loose, d))
        // Moving under b (depth 2) is fine.
        assertFalse(read.moveWouldExceedDepth(loose, b))
    }

    @Test
    fun attachmentsSurfaceForTheirNode() {
        // attachments() reads @attachment entities; none here → empty (no crash).
        val t = commands.createTask("t")
        assertTrue(read.attachments(t).isEmpty())
    }
}
