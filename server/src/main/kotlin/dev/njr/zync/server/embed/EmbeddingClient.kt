package dev.njr.zync.server.embed

import kotlin.math.sqrt

/**
 * The embedding port (mirrors the operator [dev.njr.zync.server.operator.LlmClient] pattern):
 * turn texts into vectors for semantic search. One call embeds a batch; a `null` return means
 * the backend is unavailable (unconfigured, offline, non-2xx) — callers degrade to keyword
 * search rather than failing. Implementations: [OllamaEmbeddingClient] (local, default) and the
 * deterministic fake in test sources.
 */
fun interface EmbeddingClient {
    /** Embed [texts]; returns one vector per input, or null if the backend is unavailable. */
    fun embed(texts: List<String>): List<FloatArray>?
}

/** Small vector helpers for brute-force cosine similarity (spec 2026-08-11 §embeddings). */
object Vectors {
    /** L2-normalize a copy of [v] (zero vector returned unchanged), so cosine reduces to a dot. */
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum)
        if (norm == 0.0) return v.copyOf()
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** Dot product; on normalized vectors this is cosine similarity. Mismatched lengths → 0. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s.toFloat()
    }
}
