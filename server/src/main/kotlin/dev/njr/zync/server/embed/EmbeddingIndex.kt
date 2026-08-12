package dev.njr.zync.server.embed

import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory vector index for brute-force cosine search (spec 2026-08-11 §embeddings). At
 * personal GTD scale (hundreds–low-thousands of items) a full scan of normalized vectors is a
 * few milliseconds, so no ANN structure is needed. Vectors are stored L2-normalized so a query
 * reduces to a dot product. Each entry keeps a content hash so re-embedding only happens when a
 * node's text actually changed. Thread-safe.
 *
 * v1 keeps the index in memory (rebuilt lazily from the projection). Persisting vectors as a BLOB
 * in the durable store — the upgrade path in the spec — is a follow-up; the query API stays the
 * same, so the server could later swap in `sqlite-vec` without touching callers.
 */
class EmbeddingIndex {
    private class Entry(val hash: Int, val vec: FloatArray)
    private val map = ConcurrentHashMap<String, Entry>()

    /** The content hash last indexed for [id], or null if absent. */
    fun contentHash(id: String): Int? = map[id]?.hash

    fun put(id: String, hash: Int, vec: FloatArray) {
        map[id] = Entry(hash, Vectors.normalize(vec))
    }

    fun remove(id: String) {
        map.remove(id)
    }

    fun ids(): Set<String> = map.keys.toSet()

    fun size(): Int = map.size

    /** The [topK] ids most similar to [query] (cosine), best-first. */
    fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> {
        if (topK <= 0 || map.isEmpty()) return emptyList()
        val q = Vectors.normalize(query)
        return map.entries
            .map { it.key to Vectors.dot(q, it.value.vec) }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
