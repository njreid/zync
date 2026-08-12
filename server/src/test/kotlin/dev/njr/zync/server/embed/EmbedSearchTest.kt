package dev.njr.zync.server.embed

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.web.content.ContentReadModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Embedding vector math, the in-memory index, and hybrid keyword+semantic search. */
class EmbedSearchTest {

    /** Deterministic embeddings keyed by exact text, so tests don't touch the network. */
    private class FakeEmbeddingClient(val map: Map<String, FloatArray>, val dim: Int) : EmbeddingClient {
        override fun embed(texts: List<String>): List<FloatArray> = texts.map { map[it] ?: FloatArray(dim) }
    }

    private fun f(vararg xs: Float) = xs

    @Test
    fun normalizeAndDotComputeCosine() {
        val n = Vectors.normalize(f(3f, 4f))
        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
        // cosine of identical direction is 1; orthogonal is 0.
        assertEquals(1f, Vectors.dot(Vectors.normalize(f(1f, 0f)), Vectors.normalize(f(2f, 0f))), 1e-6f)
        assertEquals(0f, Vectors.dot(Vectors.normalize(f(1f, 0f)), Vectors.normalize(f(0f, 5f))), 1e-6f)
    }

    @Test
    fun indexRanksBySimilarityAndTracksContentHash() {
        val idx = EmbeddingIndex()
        idx.put("a", hash = 1, vec = f(1f, 0f))
        idx.put("b", hash = 2, vec = f(0f, 1f))
        val top = idx.search(f(0.9f, 0.1f), 2)
        assertEquals("a", top.first().first)
        assertEquals(2, idx.size())
        assertEquals(1, idx.contentHash("a"))
        idx.remove("a")
        assertEquals(null, idx.contentHash("a"))
    }

    private fun seed(): Pair<SyncService, ContentReadModel> {
        val service = SyncService(JvmZyncDatabase.inMemory())
        service.ingestLocal(Op.SetField(id(10), id(1), EntityType.Node, Hlc(1, 0, "s"), Actor.Human, "s", 1, "title", str("alpha")))
        service.ingestLocal(Op.SetField(id(11), id(2), EntityType.Node, Hlc(2, 0, "s"), Actor.Human, "s", 2, "title", str("beta")))
        return service to ContentReadModel(service.stateStore)
    }

    @Test
    fun hybridSearchAddsSemanticHitsKeywordWouldMiss() {
        val (service, read) = seed()
        // "zzz" shares no substring with either title, but embeds onto "alpha"'s direction.
        val fake = FakeEmbeddingClient(mapOf("alpha" to f(1f, 0f), "beta" to f(0f, 1f), "zzz" to f(1f, 0f)), dim = 2)
        val ss = SemanticSearch(read, service.stateStore, fake)
        val hits = ss.search("zzz", 10)
        assertTrue(hits.any { it.id == id(1) }, "alpha found semantically")
        assertFalse(hits.any { it.id == id(2) }, "beta is near-orthogonal — filtered by minScore")
    }

    @Test
    fun keywordHitsStillComeBackAndAreNotDuplicated() {
        val (service, read) = seed()
        val fake = FakeEmbeddingClient(mapOf("alpha" to f(1f, 0f), "beta" to f(0f, 1f)), dim = 2)
        val ss = SemanticSearch(read, service.stateStore, fake)
        val hits = ss.search("alpha", 10)
        assertEquals(1, hits.count { it.id == id(1) }, "keyword hit appears exactly once")
    }

    @Test
    fun degradesToKeywordWhenNoEmbeddingBackend() {
        val (service, read) = seed()
        val ss = SemanticSearch(read, service.stateStore, client = null)
        assertFalse(ss.enabled)
        val hits = ss.search("alpha", 10)
        assertTrue(hits.any { it.id == id(1) })
        // "zzz" has no keyword match and no semantic backend → nothing.
        assertTrue(ss.search("zzz", 10).isEmpty())
    }
}
