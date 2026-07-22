package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.FtsQuery
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.content.stringContent
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
        // Skip unreviewed proposals and trashed nodes — they aren't valid filing targets.
        if ((snap.fields[dev.njr.zync.core.agent.AgentFlow.FIELD_PROPOSED] as? JsonPrimitive)?.content == "true") return null
        val status = asString(snap.fields[Fields.STATUS])
        if (status == "DROPPED") return null
        val underReference = snap.parent?.toString() == referenceRoot.toString()
        val isProject = asString(snap.fields[Fields.KIND]) == "project"
        if (!isProject && !underReference) return null
        // An archived (FILED) project is not an active Projects-tree target.
        if (isProject && !underReference && status == "FILED") return null
        return Candidate(
            nodeId = snap.entityId,
            title = asString(snap.fields[Fields.TITLE]) ?: "(untitled)",
            tree = if (underReference) "reference" else "projects",
        )
    }

    /**
     * Fraction of the CANDIDATE's tokens covered by the query — length-independent of the
     * item text, so a long item (or one with OCR text folded in) doesn't dilute the score
     * below the floor. A candidate whose words all appear in the item scores 1.0.
     */
    private fun score(queryTokens: Set<String>, snap: EntitySnapshot): Double {
        val candText = listOf(Fields.TITLE, Fields.NOTES).mapNotNull { asString(snap.fields[it]) }.joinToString(" ")
        val candTokens = FtsQuery.tokens(candText).toSet()
        if (candTokens.isEmpty()) return 0.0
        return candTokens.count { it in queryTokens }.toDouble() / candTokens.size
    }

    private fun asString(value: kotlinx.serialization.json.JsonElement?): String? = value.stringContent()
}

/** The inbox item's text used as the suggestion query (title + notes + summary + OCR text). */
internal fun queryText(snapshot: EntitySnapshot, blobText: (String) -> String?): String {
    val parts = mutableListOf<String>()
    listOf(Fields.TITLE, Fields.NOTES, Fields.SUMMARY).forEach { f ->
        snapshot.fields[f].stringContent()?.let(parts::add)
    }
    snapshot.fields[Fields.OCR_BLOB_HASH].stringContent()?.let { blobText(it)?.take(2000)?.let(parts::add) }
    return parts.joinToString("\n")
}

/**
 * A retrieval [CompletionSource]: rank Reference/Projects candidates for the firing
 * entity and serialize the result into the operator's output field. Both file-location
 * operators are instances that differ only in [trees], [limit], and [toOutput].
 */
private class FileRetrievalSource(
    private val index: ReferenceIndex,
    private val blobText: (String) -> String?,
    private val trees: Set<String>,
    private val limit: Int,
    private val toOutput: (List<Pair<Candidate, Double>>) -> String,
) : CompletionSource {
    override fun complete(request: LlmRequest, snapshot: EntitySnapshot): LlmReply =
        LlmReply.Text(toOutput(index.rank(queryText(snapshot, blobText), snapshot.entityId, trees, limit)))
}

/** The two file-location retrieval operators (GTD §6/§7) as [CompletionSource]s. */
object FileSuggesters {
    /** Up to 3 ranked `fileSuggestions` over Projects + Reference for an inbox item (§6). */
    fun suggestFile(index: ReferenceIndex, blobText: (String) -> String? = { null }): CompletionSource =
        FileRetrievalSource(index, blobText, trees = setOf("projects", "reference"), limit = 3) { ranked ->
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
            """{"${Fields.FILE_SUGGESTIONS}":$array}"""
        }

    /** The top Reference-area `proposedFileParent` for a DONE task (§7). */
    fun autoFileDone(index: ReferenceIndex, blobText: (String) -> String? = { null }): CompletionSource =
        FileRetrievalSource(index, blobText, trees = setOf("reference"), limit = 1) { ranked ->
            ranked.firstOrNull()
                ?.let { """{"${Fields.PROPOSED_FILE_PARENT}":"${it.first.nodeId}"}""" }
                ?: "{}"
        }
}
