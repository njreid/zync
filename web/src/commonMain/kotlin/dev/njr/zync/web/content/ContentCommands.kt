package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.ReadingState
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
    /** Create a content node. Top-level (parent null) it reads as an Inbox Item; filed into the
     *  Projects/Reference tree or given children it derives as a Task/Project/Reference node. */
    fun createTask(title: String, parent: Ulid? = null): Ulid = create(title, parent)

    /** Convenience alias — a "project" is just a content node that has children (type is derived). */
    fun createProject(title: String, parent: Ulid? = null): Ulid = create(title, parent)

    /**
     * Create a Reference **folder** under [parent] (reference-and-archive spec): a node carrying the
     * explicit [Fields.FOLDER] marker so it reads as a folder even while empty. Parent is normally a
     * reference folder or the reference root; the marker (not has-children) drives `typeOf`.
     */
    fun createFolder(title: String, parent: Ulid): Ulid {
        val id = ops.newId()
        ops.setField(id, Fields.TITLE, JsonPrimitive(title))
        ops.setField(id, Fields.FOLDER, JsonPrimitive(true))
        ops.move(id, parent)
        return id
    }

    /** Archive a Project (reference-and-archive spec): Move it (and its subtree) under the Archive
     *  root, making it inert — out of Next/Today/context — while staying searchable and reversible. */
    fun archiveProject(node: Ulid) = ops.move(node, WellKnownNodes.ARCHIVE_ROOT)

    /** Un-archive: Move a project back under Projects (default the Projects root), going live again. */
    fun unarchive(node: Ulid, toParent: Ulid = WellKnownNodes.PROJECTS_ROOT) = ops.move(node, toParent)

    /**
     * Link a Task to a Reference URL (reference-and-archive spec): its own `ref:<url>` boolean
     * register, so concurrent adds on two devices both survive (mergeable set, like free tags).
     * URLs are reference-tree paths (`/Personal/Family/info`) or external; blank is a no-op.
     */
    fun addReferenceLink(node: Ulid, url: String) {
        val u = url.trim().takeIf { it.isNotEmpty() } ?: return
        ops.setField(node, Fields.REF_LINK_PREFIX + u, JsonPrimitive(true))
    }

    /** Remove a Task's Reference link (clears its register). */
    fun removeReferenceLink(node: Ulid, url: String) =
        ops.setField(node, Fields.REF_LINK_PREFIX + url.trim(), JsonNull)

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

    /** Reading is personal progress, not task completion or filing. */
    fun setReadingState(node: Ulid, state: String) {
        require(state in ReadingState.ALL) { "invalid reading state" }
        ops.setField(node, Fields.READING_STATE, JsonPrimitive(state))
    }

    fun setReadingProgress(node: Ulid, percent: Int) {
        require(percent in 0..100) { "reading progress must be 0..100" }
        ops.setField(node, Fields.READING_PROGRESS, JsonPrimitive(percent))
    }

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
    fun split(parent: Ulid, childTitle: String): Ulid = addSubtask(parent, childTitle)

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

    // The old DONE→Reference proposal flow (acceptProposedFile/rejectProposedFile) is removed:
    // Projects now retire to Archive, and completed tasks no longer file into Reference
    // (reference-and-archive spec).

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

    // Content nodes carry no `kind` — their type (Inbox Item / Task / Project / Reference) is
    // DERIVED from tree location + children (see ContentReadModel.typeOf). `kind` is reserved for
    // the orthogonal entity types (context/comment/agent).
    private fun create(title: String, parent: Ulid?): Ulid {
        val id = ops.newId()
        ops.setField(id, "title", JsonPrimitive(title))
        ops.setField(id, "status", JsonPrimitive("ACTIVE"))
        if (parent != null) ops.move(id, parent)
        return id
    }
}
