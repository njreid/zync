package dev.njr.zync.web.content

import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shared UI's mutation vocabulary (GTD intents), mapped onto op-log primitives via
 * [OpEmitter]. Identical on server and phone; only the emitter differs. Trash/complete/
 * reopen are reversible `status` writes (not tombstones); [purge] is the hard tombstone.
 */
class ContentCommands(private val ops: OpEmitter) {
    fun createTask(title: String, parent: Ulid? = null): Ulid = create(title, "task", parent)
    fun createProject(title: String, parent: Ulid? = null): Ulid = create(title, "project", parent)

    fun createContext(name: String): Ulid {
        val id = ops.newId()
        ops.setField(id, "kind", JsonPrimitive("context"))
        ops.setField(id, "name", JsonPrimitive(name))
        return id
    }

    fun rename(node: Ulid, title: String) = ops.setField(node, "title", JsonPrimitive(title))
    fun setNotes(node: Ulid, notes: String) = ops.setField(node, "notes", JsonPrimitive(notes))

    fun complete(node: Ulid) = setStatus(node, "DONE")
    fun reopen(node: Ulid) = setStatus(node, "ACTIVE")
    fun trash(node: Ulid) = setStatus(node, "DROPPED")

    fun defer(node: Ulid, untilMillis: Long) = ops.setField(node, "deferUntil", JsonPrimitive(untilMillis))
    fun move(node: Ulid, newParent: Ulid) = ops.move(node, newParent)
    fun convertToProject(node: Ulid) = ops.setField(node, "kind", JsonPrimitive("project"))
    fun convertToTask(node: Ulid) = ops.setField(node, "kind", JsonPrimitive("task"))

    fun addTag(node: Ulid, context: Ulid) = ops.addTag(node, context)
    fun removeTag(node: Ulid, context: Ulid) = ops.removeTag(node, context)

    /** Permanent purge (hard tombstone) — rare; most removal is reversible [trash]. */
    fun purge(node: Ulid) = ops.tombstone(node)

    private fun setStatus(node: Ulid, status: String) = ops.setField(node, "status", JsonPrimitive(status))

    private fun create(title: String, kind: String, parent: Ulid?): Ulid {
        val id = ops.newId()
        ops.setField(id, "kind", JsonPrimitive(kind))
        ops.setField(id, "title", JsonPrimitive(title))
        ops.setField(id, "status", JsonPrimitive("ACTIVE"))
        if (parent != null) ops.move(id, parent)
        return id
    }
}
