package dev.njr.zync.server.clock

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerHlcTest {
    private class FakeStore : HlcStore {
        var saved: Hlc? = null
        override fun load(): Hlc? = saved
        override fun save(hlc: Hlc) { saved = hlc }
    }

    @Test
    fun nowPersistsEveryAdvance() {
        val store = FakeStore()
        val hlc = ServerHlc(store, clock = Clock { 1000L })
        val first = hlc.now()
        assertEquals(first, store.saved)
        val second = hlc.now()
        assertTrue(second.counter > first.counter || second.physical > first.physical)
        assertEquals(second, store.saved)
    }

    @Test
    fun loadsPriorStateOnConstruction() {
        val store = FakeStore()
        store.saved = Hlc(5000L, 3, "server")
        val hlc = ServerHlc(store, clock = Clock { 1000L }) // wall clock behind the persisted state
        val next = hlc.now()
        assertTrue(next.physical >= 5000L, "must not regress behind the persisted HLC even if wall clock is behind")
    }

    @Test
    fun observeAdvancesPastARemoteClockAndPersists() {
        val store = FakeStore()
        val hlc = ServerHlc(store, clock = Clock { 1000L })
        val remote = Hlc(9000L, 0, "phone-1")
        val observed = hlc.observe(remote)
        assertTrue(observed.physical >= 9000L)
        assertEquals(observed, store.saved)
    }
}
