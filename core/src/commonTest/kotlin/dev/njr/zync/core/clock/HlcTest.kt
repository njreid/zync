package dev.njr.zync.core.clock

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTest {
    @Test
    fun nowIncrementsCounterUnderStaleClock() {
        val gen = HlcGenerator("dev", MutableClock(100))
        val h1 = gen.now()
        val h2 = gen.now()
        assertTrue(h2 > h1)
        assertEquals(h1.physical, h2.physical)
        assertEquals(h1.counter + 1, h2.counter)
    }

    @Test
    fun nowAdvancesPhysicalAndResetsCounter() {
        val clock = MutableClock(100)
        val gen = HlcGenerator("dev", clock)
        gen.now(); gen.now() // counter reaches 1
        clock.millis = 200
        val h = gen.now()
        assertEquals(200, h.physical)
        assertEquals(0, h.counter)
    }

    @Test
    fun observeAdvancesPastRemote() {
        val gen = HlcGenerator("dev", MutableClock(50))
        val remote = Hlc(100, 5, "other")
        val h = gen.observe(remote)
        assertTrue(h > remote)
        assertEquals(100, h.physical)
        assertEquals(6, h.counter)
    }

    @Test
    fun observeTakesMaxCounterWhenPhysicalsEqual() {
        val gen = HlcGenerator("dev", MutableClock(100))
        gen.now() // last = (100,0,dev)
        val h = gen.observe(Hlc(100, 3, "other")) // p==last==remote → max(0,3)+1
        assertEquals(100, h.physical)
        assertEquals(4, h.counter)
    }

    @Test
    fun observeThenNowStaysMonotonic() {
        val gen = HlcGenerator("dev", MutableClock(10))
        val h1 = gen.observe(Hlc(1000, 0, "other"))
        val h2 = gen.now()
        assertTrue(h2 > h1)
    }

    @Test
    fun totalOrderIsPhysicalThenCounterThenDevice() {
        assertTrue(Hlc(1, 0, "aaa") < Hlc(1, 0, "bbb")) // device tiebreak
        assertTrue(Hlc(1, 1, "aaa") > Hlc(1, 0, "zzz")) // counter beats device
        assertTrue(Hlc(2, 0, "aaa") > Hlc(1, 9, "zzz")) // physical beats counter
    }

    @Test
    fun serializesRoundTrip() {
        val h = Hlc(123, 4, "dev")
        assertEquals(h, Json.decodeFromString(Hlc.serializer(), Json.encodeToString(Hlc.serializer(), h)))
    }

    @Test
    fun packUnpackRoundTripWithColonsInDeviceId() {
        val h = Hlc(123, 4, "phone:model:7")
        assertEquals(h, Hlc.unpack(h.pack()))
    }
}
