package dev.njr.zync.server.embed

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.merge.project
import dev.njr.zync.core.state.EntitySnapshot
import dev.njr.zync.core.state.StateStore
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.NodeView
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hybrid keyword + semantic search (spec 2026-08-11 §embeddings). Keyword hits (the existing
 * substring index) come first — they're exact — then semantic hits not already present, so
 * meaning-based matches extend rather than replace lexical ones. Degrades to keyword-only when
 * no [client] is configured, so the default deployment behaves exactly as before.
 *
 * The corpus is embedded **lazily**: each search refreshes the index for content whose text
 * changed since it was last embedded (bounded per call). This keeps the write path untouched and
 * needs no background worker; the trade-off is that the first search after a burst of edits pays
 * to embed them. Embedding is network I/O, so callers should invoke [search] off the event loop.
 */
class SemanticSearch(
    private val read: ContentReadModel,
    private val store: StateStore,
    private val client: EmbeddingClient?,
    private val index: EmbeddingIndex = EmbeddingIndex(),
    private val maxCorpusPerRefresh: Int = 512,
    /** Minimum cosine similarity for a semantic hit — filters out near-orthogonal noise. */
    private val minScore: Float = 0.2f,
) {
    val enabled: Boolean get() = client != null

    private fun textOf(s: EntitySnapshot): String? {
        val text = listOf(Fields.TITLE, Fields.NOTES, Fields.SUMMARY)
            .mapNotNull { (s.fields[it] as? JsonPrimitive)?.content }
            .joinToString(" ")
            .trim()
        return text.ifBlank { null }
    }

    /** Serializes [refreshCorpus] so concurrent searches (each called off the event loop, so they
     *  can run on different threads at once) can't race the read-dirty-set → embed → write-back
     *  sequence against each other — e.g. one re-inserting a vector for a node the other's
     *  removal pass just dropped. */
    private val refreshLock = Any()

    /** Bring the index in line with the current projection: drop removed nodes, embed changed ones. */
    private fun refreshCorpus(client: EmbeddingClient): Unit = synchronized(refreshLock) {
        val live = HashMap<String, Pair<Int, String>>() // id -> (contentHash, text)
        for (s in store.project().values) {
            if (!s.alive) continue
            val text = textOf(s) ?: continue
            live[s.entityId.toString()] = text.hashCode() to text
        }
        (index.ids() - live.keys).forEach { index.remove(it) }
        val dirty = live.entries.filter { index.contentHash(it.key) != it.value.first }.take(maxCorpusPerRefresh)
        if (dirty.isEmpty()) return@synchronized
        val vecs = client.embed(dirty.map { it.value.second }) ?: return@synchronized
        dirty.forEachIndexed { i, e -> vecs.getOrNull(i)?.let { index.put(e.key, e.value.first, it) } }
    }

    /** Semantic-only ids for [query], best-first; empty when disabled/unavailable/blank. */
    fun semanticIds(query: String, limit: Int): List<Ulid> {
        val c = client ?: return emptyList()
        if (query.isBlank()) return emptyList()
        refreshCorpus(c)
        val qv = c.embed(listOf(query))?.firstOrNull() ?: return emptyList()
        return index.search(qv, limit)
            .filter { it.second >= minScore }
            .mapNotNull { runCatching { Ulid.parse(it.first) }.getOrNull() }
    }

    /** Hybrid results as NodeViews (drop-in for `ContentReadModel::search`): keyword then semantic. */
    fun search(query: String, limit: Int): List<NodeView> {
        val keyword = read.search(query, limit)
        if (client == null) return keyword
        val seen = keyword.mapTo(HashSet()) { it.id.toString() }
        val extra = semanticIds(query, limit)
            .filter { seen.add(it.toString()) }
            .mapNotNull { read.node(it) }
            .filter { it.status != "DROPPED" }
        return (keyword + extra).take(limit)
    }
}
