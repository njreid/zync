package dev.njr.zync.server.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SeqAllocatorTest {
    @Test
    fun assignsStrictlyMonotonicGapFreeFromZero() {
        val seq = SeqAllocator()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), (1..5).map { seq.next() })
        assertEquals(5L, seq.head())
    }

    @Test
    fun resumesFromPersistedHead() {
        val seq = SeqAllocator(initialHead = 100)
        assertEquals(101L, seq.next())
        assertEquals(102L, seq.next())
        assertEquals(102L, seq.head())
    }

    @Test
    fun isGapFreeUnderConcurrentIngest() {
        val seq = SeqAllocator()
        val results = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val threads = (1..8).map {
            Thread {
                repeat(1000) { results.add(seq.next()) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // 8 * 1000 unique, contiguous seqs with no duplicates or gaps
        assertEquals(8000, results.size)
        assertEquals((1L..8000L).toSet(), results)
        assertEquals(8000L, seq.head())
    }
}
