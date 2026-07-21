package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.FtsQuery
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One candidate filing location (GTD triage §6): a Project or a Reference-tree area. */
data class Candidate(val nodeId: Ulid, val title: String, val tree: String) // "projects" | "reference"

/**
 * Keyword file-location suggester (GTD triage §6, keyword-only v1 — embeddings are a
 * documented follow-up). Scores Projects/Reference candidates by the fraction of the
 * inbox item's query tokens that appear in the candidate's title/notes, keeps those at
 * or above [floor], and returns the top matches. Deterministic; no LLM, no external calls.
 */
class ReferenceIndex(
    private val store: StateStore,
    private val referenceRoot: Ulid = WellKnownNodes.REFERENCE_ROOT,
    private val floor: Double = 0.15,
) {
    fun rank(query: String, exclude: Ulid, trees: Set<String>, limit: Int = 3): List<Pair<Candidate, Double>> {
        val queryTokens = FtsQuery.tokens(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()
        val excludeParent = store.getParent(exclude)?.toString()
        return store.project().values.asSequence()
            .filter { it.alive }
            .mapNotNull { snap -> candidate(snap)?.let { it to snap } }
            .filter { it.first.tree in trees }
            .filter { it.first.nodeId != exclude && it.first.nodeId.toString() != excludeParent }
            .map { (cand, snap) -> cand to score(queryTokens, snap) }
            .filter { it.second >= floor }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
    }

    private fun candidate(snap: EntitySnapshot): Candidate? {
        val underReference = snap.parent?.toString() == referenceRoot.toString()
        val isProject = asString(snap.fields[Fields.KIND]) == "project"
        if (!isProject && !underReference) return null
        return Candidate(
            nodeId = snap.entityId,
            title = asString(snap.fields[Fields.TITLE]) ?: "(untitled)",
            tree = if (underReference) "reference" else "projects",
        )
    }

    private fun score(queryTokens: Set<String>, snap: EntitySnapshot): Double {
        val candText = listOf(Fields.TITLE, Fields.NOTES).mapNotNull { asString(snap.fields[it]) }.joinToString(" ")
        val candTokens = FtsQuery.tokens(candText).toSet()
        if (candTokens.isEmpty()) return 0.0
        return queryTokens.count { it in candTokens }.toDouble() / queryTokens.size
    }

    private fun asString(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
}

/** The inbox item's text used as the suggestion query (title + notes + summary + OCR text). */
internal fun queryText(snapshot: EntitySnapshot, blobText: (String) -> String?): String {
    val parts = mutableListOf<String>()
    listOf(Fields.TITLE, Fields.NOTES, Fields.SUMMARY).forEach { f ->
        (snapshot.fields[f] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.let(parts::add)
    }
    (snapshot.fields[Fields.OCR_BLOB_HASH] as? JsonPrimitive)?.content?.let { blobText(it)?.take(2000)?.let(parts::add) }
    return parts.joinToString("\n")
}

/** Emits up to 3 ranked file-location suggestions as the `fileSuggestions` field (GTD §6). */
class SuggestFileCompletionSource(
    private val index: ReferenceIndex,
    private val blobText: (String) -> String? = { null },
) : CompletionSource {
    override fun complete(request: LlmRequest, snapshot: EntitySnapshot): LlmReply {
        val ranked = index.rank(queryText(snapshot, blobText), exclude = snapshot.entityId, trees = setOf("projects", "reference"))
        val array = buildJsonArray {
            ranked.forEach { (c, s) ->
                add(
                    buildJsonObject {
                        put("targetId", c.nodeId.toString())
                        put("title", c.title)
                        put("tree", c.tree)
                        put("score", s)
                    },
                )
            }
        }
        return LlmReply.Text("""{"${Fields.FILE_SUGGESTIONS}":$array}""")
    }
}

/** Proposes the top Reference-area parent for a DONE task as `proposedFileParent` (GTD §7). */
class AutoFileCompletionSource(
    private val index: ReferenceIndex,
    private val blobText: (String) -> String? = { null },
) : CompletionSource {
    override fun complete(request: LlmRequest, snapshot: EntitySnapshot): LlmReply {
        val top = index.rank(queryText(snapshot, blobText), exclude = snapshot.entityId, trees = setOf("reference"), limit = 1)
            .firstOrNull()
        return LlmReply.Text(
            if (top != null) """{"${Fields.PROPOSED_FILE_PARENT}":"${top.first.nodeId}"}""" else "{}",
        )
    }
}
