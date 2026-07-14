package dev.njr.zync.server.operator

import dev.njr.zync.core.operator.ReadScopeHandle
import dev.njr.zync.core.state.EntitySnapshot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A resolved read-scope predicate (spec §7 open question: small named scopes,
 * not a second query engine). [reads] declares the register fields the
 * predicate consults *and* the fields fed to the LLM prompt — together they
 * are the operator's entire view of the entity, so they also define its
 * [dev.njr.zync.core.operator.InputVersion] and the edges used for static
 * cycle detection.
 */
class ReadScope(
    val ref: String,
    val reads: Set<String>,
    private val predicate: (EntitySnapshot) -> Boolean,
) {
    fun matches(snapshot: EntitySnapshot): Boolean = predicate(snapshot)
}

/** Maps a manifest's opaque [ReadScopeHandle] to a runtime [ReadScope]. */
class ReadScopeResolver(scopes: List<ReadScope>) {
    private val byRef = scopes.associateBy { it.ref }

    fun resolve(handle: ReadScopeHandle): ReadScope? = byRef[handle.ref]

    companion object {
        /** The built-in scopes every deployment knows. */
        fun default(): ReadScopeResolver = ReadScopeResolver(listOf(ReadScopes.inboxTask))
    }
}

/** Built-in named read scopes. */
object ReadScopes {
    const val INBOX_TASK_REF = "inbox-task"

    /**
     * The reference scope from the spec: `kind=task AND parent=INBOX AND tags=∅`.
     * The server's inbox is the root level (the web UI's inbox view lists
     * root-parented nodes), and only live, still-ACTIVE tasks qualify.
     */
    val inboxTask: ReadScope = ReadScope(
        ref = INBOX_TASK_REF,
        reads = setOf("kind", "status", "title", "notes"),
    ) { s ->
        s.alive &&
            s.parent == null &&
            s.tags.isEmpty() &&
            s.fields["kind"].asString() == "task" &&
            (s.fields["status"].asString() ?: "ACTIVE") == "ACTIVE"
    }

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
