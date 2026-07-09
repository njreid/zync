package dev.njr.zync.core.clock

/**
 * Injected wall-clock source. Merge logic must never read an ambient clock; all
 * physical time enters through a [Clock] so tests are deterministic and HLC
 * generation is reproducible.
 */
fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    fun nowMillis(): Long
}
