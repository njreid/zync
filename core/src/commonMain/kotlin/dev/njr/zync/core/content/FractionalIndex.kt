package dev.njr.zync.core.content

/**
 * Fractional indexing for sibling ordering (GTD triage spec §3) — the ordering
 * primitive behind FIFO inbox, project reorder, and loose-task reorder.
 *
 * A rank is a string of digits from [DIGITS] interpreted as a base-36 fraction in
 * (0, 1): **lexicographic string order == fractional order**, so ranks sort with a
 * plain string comparison (no numeric parsing) and merge as an ordinary LWW
 * `Op.SetField` with zero new merge code. To insert between two neighbours you mint
 * a key that sorts strictly between them; there is always room (append another
 * digit), so inserts never need to touch siblings.
 *
 * Convergence notes (spec §3):
 * - Two devices re-ranking the *same* node → LWW picks one rank (fine).
 * - Two devices inserting *different* nodes at the same slot may mint equal keys →
 *   callers break ties by node ULID for a deterministic, convergent order.
 * - Keys grow by one digit per repeated same-slot insert; [rebalance] rewrites a
 *   sibling list to short, evenly-spaced keys when any key gets too long (rare).
 */
object FractionalIndex {
    /** Ordered digit alphabet; ascending ASCII, so String comparison == rank order. */
    const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** Renormalize once a sibling's key reaches this length (spec §3 rebalance). */
    const val REBALANCE_THRESHOLD = 12

    private val BASE = DIGITS.length
    private val FIRST = DIGITS.first()

    private fun idx(c: Char): Int = DIGITS.indexOf(c)
    private fun digit(i: Int): Char = DIGITS[i]

    /**
     * A key strictly between [lower] and [upper] in rank order; `null` is an open
     * end (before the first / after the last). Requires `lower < upper` when both
     * are present. The result never ends in the smallest digit, preserving the
     * invariant later inserts rely on.
     */
    fun between(lower: String?, upper: String?): String {
        require(lower == null || upper == null || lower < upper) {
            "expected lower < upper, got lower=$lower upper=$upper"
        }
        return midpoint(lower ?: "", upper)
    }

    /**
     * [count] short, evenly-spaced ranks in ascending order — for seeding a fresh
     * sibling list or rebalancing one whose keys have grown. Balanced by bisection,
     * so keys stay near-minimal length.
     */
    fun rebalance(count: Int): List<String> {
        require(count >= 0) { "count must be >= 0, was $count" }
        val out = arrayOfNulls<String>(count)
        fun fill(lo: String?, hi: String?, from: Int, to: Int) {
            if (from > to) return
            val mid = (from + to) / 2
            val key = between(lo, hi)
            out[mid] = key
            fill(lo, key, from, mid - 1)
            fill(key, hi, mid + 1, to)
        }
        fill(null, null, 0, count - 1)
        @Suppress("UNCHECKED_CAST")
        return (out as Array<String>).toList()
    }

    /** True once [rank] should trigger a sibling-list [rebalance]. */
    fun needsRebalance(rank: String): Boolean = rank.length >= REBALANCE_THRESHOLD

    /**
     * The classic fractional-index midpoint: a string strictly greater than [lower]
     * (padded with the smallest digit) and, when [upper] is non-null, strictly less
     * than it. [lower] is "" for an open lower bound.
     */
    private fun midpoint(lower: String, upper: String?): String {
        // Descend through any shared prefix, then split at the first differing digit.
        if (upper != null) {
            var n = 0
            while (n < upper.length && (if (n < lower.length) lower[n] else FIRST) == upper[n]) n++
            if (n > 0) {
                val lowerRest = if (n < lower.length) lower.substring(n) else ""
                return upper.substring(0, n) + midpoint(lowerRest, upper.substring(n))
            }
        }
        val digitLower = if (lower.isNotEmpty()) idx(lower[0]) else 0
        val digitUpper = if (upper != null && upper.isNotEmpty()) idx(upper[0]) else BASE
        return if (digitUpper - digitLower > 1) {
            // Room between the leading digits: pick the middle one.
            digit((digitLower + digitUpper) / 2).toString()
        } else if (upper != null && upper.length > 1) {
            // Leading digits are consecutive but upper has a tail — its first digit works.
            upper.substring(0, 1)
        } else {
            // No room at this position: keep lower's leading digit and go deeper.
            val lowerRest = if (lower.isNotEmpty()) lower.substring(1) else ""
            digit(digitLower).toString() + midpoint(lowerRest, null)
        }
    }
}
