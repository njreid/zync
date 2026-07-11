package dev.njr.zync.web.content

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.apply
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.core.state.StateStore
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FixedClock(private val ms: Long) : Clock { override fun nowMillis() = ms }

/** Applies emitted ops to [store] with monotonically increasing HLCs (later intent wins). */
private class RecordingEmitter(private val store: StateStore) : OpEmitter {
    private var counter = 0
    val emitted = mutableListOf<Op>()

    override fun newId(): Ulid = uid()
    override fun setField(entity: Ulid, field: String, value: JsonElement) =
        emit(Op.SetField(uid(), entity, EntityType.Node, hlc(), Actor.Human, "dev", 0, field, value))
    override fun move(node: Ulid, newParent: Ulid) =
        emit(Op.Move(uid(), node, EntityType.Node, hlc(), Actor.Human, "dev", 0, newParent))
    override fun addTag(node: Ulid, context: Ulid) =
        emit(Op.AddTag(uid(), node, EntityType.Tag, hlc(), Actor.Human, "dev", 0, context))
    override fun removeTag(node: Ulid, context: Ulid) =
        emit(Op.RemoveTag(uid(), node, EntityType.Tag, hlc(), Actor.Human, "dev", 0, context))
    override fun tombstone(entity: Ulid) =
        emit(Op.Tombstone(uid(), entity, EntityType.Node, hlc(), Actor.Human, "dev", 0))

    private fun uid(): Ulid { val n = ++counter; return Ulid.generate(FixedClock(n.toLong()), Random(n.toLong())) }
    private fun hlc(): Hlc = Hlc((++counter).toLong(), 0, "dev")
    private fun emit(op: Op) { emitted += op; apply(op, store) }
}

class ContentTest {
    private val store = InMemoryStateStore()
    private val commands = ContentCommands(RecordingEmitter(store))
    private val read = ContentReadModel(store)

    @Test
    fun createTaskShowsInInboxWithFields() {
        val inbox = commands.createProject("Inbox")
        val t = commands.createTask("Buy milk", parent = inbox)
        val view = read.node(t)!!
        assertEquals("task", view.kind)
        assertEquals("Buy milk", view.title)
        assertEquals("ACTIVE", view.status)
        assertEquals(inbox, view.parent)
        assertTrue(read.inbox(inbox).any { it.id == t })
    }

    @Test
    fun completeAndTrashHideFromInbox() {
        val inbox = commands.createProject("Inbox")
        val done = commands.createTask("done", inbox)
        val dropped = commands.createTask("dropped", inbox)
        val kept = commands.createTask("kept", inbox)
        commands.complete(done)
        commands.trash(dropped)
        val ids = read.inbox(inbox).map { it.id }.toSet()
        assertTrue(kept in ids)
        assertFalse(done in ids)
        assertFalse(dropped in ids)
        // complete is reversible — the node is still alive
        assertEquals("DONE", read.node(done)!!.status)
    }

    @Test
    fun deferHidesUntilDate() {
        val inbox = commands.createProject("Inbox")
        val t = commands.createTask("later", inbox)
        commands.defer(t, untilMillis = 5_000)
        assertFalse(read.inbox(inbox, now = 1_000).any { it.id == t })
        assertTrue(read.inbox(inbox, now = 9_000).any { it.id == t })
    }

    @Test
    fun moveConvertTagsReflect() {
        val a = commands.createProject("A")
        val b = commands.createProject("B")
        val t = commands.createTask("t", a)
        commands.move(t, b)
        assertEquals(b, read.node(t)!!.parent)

        commands.convertToProject(t)
        assertEquals("project", read.node(t)!!.kind)

        val ctx = commands.createContext("errands")
        commands.addTag(t, ctx)
        assertTrue(read.node(t)!!.tags.contains(ctx))
        commands.removeTag(t, ctx)
        assertFalse(read.node(t)!!.tags.contains(ctx))
        // contexts are listed separately, not among task/project nodes
        assertTrue(read.contexts().any { it.id == ctx && it.name == "errands" })
        assertFalse(read.nodes().any { it.id == ctx })
    }

    @Test
    fun purgeTombstonesNode() {
        val t = commands.createTask("temp")
        commands.purge(t)
        assertNull(read.node(t))
    }
}
