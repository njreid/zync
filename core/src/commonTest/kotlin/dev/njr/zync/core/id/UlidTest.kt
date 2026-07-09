package dev.njr.zync.core.id

import dev.njr.zync.core.clock.FixedClock
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UlidTest {
    private val crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    @Test
    fun encodesTo26CharCrockford() {
        val ulid = Ulid.generate(FixedClock(1_720_000_000_000), Random(1))
        assertEquals(26, ulid.toString().length)
        assertTrue(ulid.toString().all { it in crockford }, "unexpected chars in $ulid")
    }

    @Test
    fun roundTripsThroughString() {
        val ulid = Ulid.generate(FixedClock(1_720_000_000_000), Random(7))
        assertEquals(ulid, Ulid.parse(ulid.toString()))
        assertEquals(ulid.toString(), Ulid.parse(ulid.toString()).toString())
    }

    @Test
    fun recoversEmbeddedTimestamp() {
        val ms = 1_720_000_000_000L
        assertEquals(ms, Ulid.generate(FixedClock(ms), Random(3)).timestampMillis)
        assertEquals(0L, Ulid.generate(FixedClock(0), Random(3)).timestampMillis)
        val maxMs = 0xFFFF_FFFF_FFFFL
        assertEquals(maxMs, Ulid.generate(FixedClock(maxMs), Random(3)).timestampMillis)
    }

    @Test
    fun laterTimestampSortsGreaterLexically() {
        val a = Ulid.generate(FixedClock(1000), Random(1))
        val b = Ulid.generate(FixedClock(2000), Random(1))
        assertTrue(a < b)
        // lexical order of the string must match chronological order
        assertTrue(a.toString() < b.toString())
    }

    @Test
    fun isDeterministicUnderFixedClockAndRng() {
        val a = Ulid.generate(FixedClock(1234), Random(99))
        val b = Ulid.generate(FixedClock(1234), Random(99))
        assertEquals(a, b)
    }

    @Test
    fun distinctEntropyProducesDistinctUlids() {
        val clock = FixedClock(1234)
        val rng = Random(99)
        val a = Ulid.generate(clock, rng)
        val b = Ulid.generate(clock, rng) // same instant, advanced RNG
        assertNotEquals(a, b)
    }

    @Test
    fun parseRejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> { Ulid.parse("TOOSHORT") }
    }

    @Test
    fun parseRejectsInvalidCharacter() {
        val valid = Ulid.generate(FixedClock(5), Random(5)).toString()
        val corrupted = "U" + valid.substring(1) // 'U' is excluded from Crockford
        assertFailsWith<IllegalArgumentException> { Ulid.parse(corrupted) }
    }

    @Test
    fun parseNormalizesLowercase() {
        val ulid = Ulid.generate(FixedClock(5555), Random(2))
        assertEquals(ulid, Ulid.parse(ulid.toString().lowercase()))
    }

    @Test
    fun serializesAsJsonString() {
        val ulid = Ulid.generate(FixedClock(42), Random(42))
        val json = Json.encodeToString(Ulid.serializer(), ulid)
        assertEquals("\"$ulid\"", json)
        assertEquals(ulid, Json.decodeFromString(Ulid.serializer(), json))
    }
}
