package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.Status
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import kotlinx.serialization.json.JsonElement
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

    /** The URL of a shared link; null/blank clears. */
    fun setLink(node: Ulid, url: String?) =
        ops.setField(node, Fields.LINK_URL, url?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)

    /** Mark "waiting for" a person: set the person + status WAITING; blank clears both (back to ACTIVE). */
    fun waitingFor(node: Ulid, name: String?) {
        val who = name?.trim()?.takeIf { it.isNotEmpty() }
        setPerson(node, who)
        setStatus(node, if (who == null) Status.ACTIVE else Status.WAITING)
    }

    /** Set a node's sibling-order fractional index (GTD triage §3; computed by the read model). */
    fun setRank(node: Ulid, rank: String) = ops.setField(node, Fields.RANK, JsonPrimitive(rank))

    /** Set a node's coarse effort size (GTD triage §4): S|M|L; null/invalid clears. */
    fun setSize(node: Ulid, size: String?) =
        ops.setField(node, Fields.SIZE, size?.takeIf { it in Size.ALL }?.let(::JsonPrimitive) ?: JsonNull)

    /**
     * Split during triage (spec §4: "add a subtask ⇒ it becomes a project"): add the
     * child and make the parent a project. Returns the new child id.
     */
    fun split(parent: Ulid, childTitle: String): Ulid {
        val child = addSubtask(parent, childTitle)
        convertToProject(parent)
        return child
    }

    /** File a node into the Reference tree (GTD triage §7): status FILED + Move under the root. */
    fun file(node: Ulid) {
        ops.setField(node, Fields.STATUS, JsonPrimitive(Status.FILED))
        ops.move(node, WellKnownNodes.REFERENCE_ROOT)
    }

    /** Accept a file-location suggestion chip (GTD §6): the human Move, then clear the field. */
    fun acceptFileSuggestion(node: Ulid, target: Ulid) {
        ops.move(node, target)
        ops.setField(node, Fields.FILE_SUGGESTIONS, JsonNull)
    }

    /** Dismiss the file-location suggestions without filing. */
    fun dismissFileSuggestions(node: Ulid) = ops.setField(node, Fields.FILE_SUGGESTIONS, JsonNull)

    /** Accept the DONE→Reference proposal (GTD §7, Q5): Move under it + status FILED + clear. */
    fun acceptProposedFile(node: Ulid, target: Ulid) {
        ops.move(node, target)
        ops.setField(node, Fields.STATUS, JsonPrimitive(Status.FILED))
        ops.setField(node, Fields.PROPOSED_FILE_PARENT, JsonNull)
    }

    /** Reject the DONE→Reference proposal (clear the operator field). */
    fun rejectProposedFile(node: Ulid) = ops.setField(node, Fields.PROPOSED_FILE_PARENT, JsonNull)

    /**
     * Accept a bot's proposed field edit (external-op-api §4): emit the real SetField as a
     * human op (so it wins the merge), then tombstone the suggestion node. The route reads
     * the suggestion's target/field/value and passes them in.
     */
    fun acceptSuggestion(suggestion: Ulid, target: Ulid, field: String, value: JsonElement) {
        ops.setField(target, field, value)
        ops.tombstone(suggestion)
    }

    /** Reject a suggestion: tombstone it, no change to the target. */
    fun rejectSuggestion(suggestion: Ulid) = ops.tombstone(suggestion)
    fun convertToProject(node: Ulid) = ops.setField(node, "kind", JsonPrimitive("project"))
    fun convertToTask(node: Ulid) = ops.setField(node, "kind", JsonPrimitive("task"))

    fun addTag(node: Ulid, context: Ulid) = ops.addTag(node, context)
    fun removeTag(node: Ulid, context: Ulid) = ops.removeTag(node, context)

    /** Add a free-form tag (mergeable per-label): its own `tag:<label>` boolean register. */
    fun addFreeTag(node: Ulid, label: String) {
        val l = label.trim().removePrefix("#").trim().takeIf { it.isNotEmpty() } ?: return
        ops.setField(node, Fields.FREE_TAG_PREFIX + l, JsonPrimitive(true))
    }

    /** Remove a free-form tag (clears its register). */
    fun removeFreeTag(node: Ulid, label: String) =
        ops.setField(node, Fields.FREE_TAG_PREFIX + label.trim(), JsonNull)

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
