package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.json.JsonNull
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

    /** Rename a context (contexts carry `name`, not `title`). */
    fun renameContext(context: Ulid, name: String) = ops.setField(context, Fields.NAME, JsonPrimitive(name))

    /** Add a comment/annotation as a child node (planning + discussion live in the tree). */
    fun addComment(parent: Ulid, text: String): Ulid {
        val id = ops.newId()
        ops.setField(id, "kind", JsonPrimitive("comment"))
        ops.setField(id, "title", JsonPrimitive(text))
        ops.move(id, parent)
        return id
    }

    /** Decompose a node by adding a subtask under it. */
    fun addSubtask(parent: Ulid, title: String): Ulid = createTask(title, parent)

    fun rename(node: Ulid, title: String) = ops.setField(node, "title", JsonPrimitive(title))
    fun setNotes(node: Ulid, notes: String) = ops.setField(node, "notes", JsonPrimitive(notes))

    fun complete(node: Ulid) = setStatus(node, "DONE")
    fun reopen(node: Ulid) = setStatus(node, "ACTIVE")
    fun trash(node: Ulid) = setStatus(node, "DROPPED")

    fun defer(node: Ulid, untilMillis: Long) = ops.setField(node, Fields.DEFER_UNTIL, JsonPrimitive(untilMillis))

    /** Hard due date (epoch millis, UTC-noon convention — see DueDates); null clears. */
    fun setDueDate(node: Ulid, millis: Long?) =
        ops.setField(node, Fields.DUE_DATE, millis?.let(::JsonPrimitive) ?: JsonNull)

    /** The responsible/waiting-on person's display name; null/blank clears. */
    fun setPerson(node: Ulid, name: String?) =
        ops.setField(node, Fields.PERSON, name?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    fun move(node: Ulid, newParent: Ulid) = ops.move(node, newParent)
    fun convertToProject(node: Ulid) = ops.setField(node, "kind", JsonPrimitive("project"))
    fun convertToTask(node: Ulid) = ops.setField(node, "kind", JsonPrimitive("task"))

    fun addTag(node: Ulid, context: Ulid) = ops.addTag(node, context)
    fun removeTag(node: Ulid, context: Ulid) = ops.removeTag(node, context)

    /**
     * Accept an agent proposal: a human op clearing the `proposed` flag, so the node
     * becomes ordinary content (spec §8 — acceptance is a human op, never implicit).
     */
    fun acceptProposal(node: Ulid) = ops.setField(node, AgentFlow.FIELD_PROPOSED, JsonPrimitive(false))

    /**
     * Reject an agent proposal: reversible [trash] plus clearing the flag, so it
     * leaves the review panel without ever rendering as content ([purge] if truly
     * unwanted).
     */
    fun rejectProposal(node: Ulid) {
        trash(node)
        ops.setField(node, AgentFlow.FIELD_PROPOSED, JsonPrimitive(false))
    }

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
