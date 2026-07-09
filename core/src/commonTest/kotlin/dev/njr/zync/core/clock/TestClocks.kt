package dev.njr.zync.core.clock

/** Clock frozen at a fixed instant. */
class FixedClock(private val millis: Long) : Clock {
    override fun nowMillis(): Long = millis
}

/** Clock whose instant can be advanced by tests. */
class MutableClock(var millis: Long) : Clock {
    override fun nowMillis(): Long = millis
}
