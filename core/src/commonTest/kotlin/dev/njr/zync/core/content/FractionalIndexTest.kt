package dev.njr.zync.core.content

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FractionalIndexTest {
    @Test
    fun betweenOpenEndsIsAValidMidKey() {
        val k = FractionalIndex.between(null, null)
        assertTrue(k.isNotEmpty())
        // Leaves room on both sides.
        assertTrue(FractionalIndex.between(null, k) < k)
        assertTrue(FractionalIndex.between(k, null) > k)
    }

    @Test
    fun betweenIsStrictlyOrdered() {
        val a = FractionalIndex.between(null, null)
        val before = FractionalIndex.between(null, a)
        val after = FractionalIndex.between(a, null)
        val mid = FractionalIndex.between(before, a)
        assertTrue(before < a && a < after, "expected before<$a<after, got $before / $after")
        assertTrue(before < mid && mid < a, "expected $before<$mid<$a")
    }

    @Test
    fun betweenAdjacentKeysAlwaysFindsRoom() {
        // Repeatedly insert between the two closest keys — must always succeed.
        var lo = FractionalIndex.between(null, null)
        var hi = FractionalIndex.between(lo, null)
        repeat(200) {
            val mid = FractionalIndex.between(lo, hi)
            assertTrue(lo < mid && mid < hi, "iteration $it: $lo < $mid < $hi failed")
            hi = mid // keep squeezing toward lo
        }
    }

    @Test
    fun keysNeverEndInSmallestDigit() {
        // The invariant later inserts rely on.
        var prev: String? = null
        repeat(300) {
            val k = FractionalIndex.between(prev, null)
            assertTrue(k.last() != FractionalIndex.DIGITS.first(), "key $k ended in smallest digit")
            prev = k
        }
    }

    @Test
    fun rejectsLowerNotBelowUpper() {
        assertFails { FractionalIndex.between("m", "m") }
        assertFails { FractionalIndex.between("n", "m") }
    }

    @Test
    fun rebalanceProducesAscendingDistinctKeys() {
        for (n in listOf(0, 1, 2, 5, 37, 100)) {
            val keys = FractionalIndex.rebalance(n)
            assertEquals(n, keys.size)
            assertEquals(keys.sorted(), keys, "rebalance($n) not ascending: $keys")
            assertEquals(n, keys.toSet().size, "rebalance($n) had duplicates: $keys")
            keys.forEach { assertTrue(it.last() != FractionalIndex.DIGITS.first()) }
        }
    }

    @Test
    fun needsRebalanceAtThreshold() {
        assertTrue(!FractionalIndex.needsRebalance("a"))
        assertTrue(FractionalIndex.needsRebalance("a".repeat(FractionalIndex.REBALANCE_THRESHOLD)))
    }

    /**
     * Property: insert nodes at random positions in a growing ordered list, always
     * minting the key between the two current neighbours. The list must stay sorted
     * by key and every key must be unique — the core guarantee reorder/FIFO rely on.
     */
    @Test
    fun randomInsertionsStaySortedAndUnique() {
        for (seed in 1..20) {
            val rng = Random(seed)
            val keys = ArrayList<String>() // kept sorted ascending
            repeat(500) {
                val pos = rng.nextInt(keys.size + 1) // insert index 0..size
                val lower = keys.getOrNull(pos - 1)
                val upper = keys.getOrNull(pos)
                val key = FractionalIndex.between(lower, upper)
                assertTrue(lower == null || lower < key, "seed $seed: $lower !< $key")
                assertTrue(upper == null || key < upper, "seed $seed: $key !< $upper")
                keys.add(pos, key)
            }
            assertEquals(keys.sorted(), keys, "seed $seed: list drifted out of order")
            assertEquals(keys.size, keys.toSet().size, "seed $seed: duplicate keys minted")
        }
    }
}
