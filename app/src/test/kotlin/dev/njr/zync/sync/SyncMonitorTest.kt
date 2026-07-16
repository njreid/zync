package dev.njr.zync.sync

import dev.njr.zync.ui.settings.normalizeInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sync tile's state priority + pasted-invite tolerance. */
class SyncMonitorTest {
    @Test
    fun statePriorityTopWins() {
        // Unpaired trumps everything — there is nothing to sync with.
        assertEquals(SyncState.Unpaired, SyncMonitor.state(paired = false, syncing = true, online = false, lastOk = false))
        // In-flight beats connectivity verdicts.
        assertEquals(SyncState.Syncing, SyncMonitor.state(paired = true, syncing = true, online = false, lastOk = false))
        // No network explains a failure better than "server down".
        assertEquals(SyncState.NoNetwork, SyncMonitor.state(paired = true, syncing = false, online = false, lastOk = false))
        // Online but the last attempt failed: the server side is the problem.
        assertEquals(SyncState.ServerUnreachable, SyncMonitor.state(paired = true, syncing = false, online = true, lastOk = false))
        assertEquals(SyncState.Connected, SyncMonitor.state(paired = true, syncing = false, online = true, lastOk = true))
        // Never synced yet (paired + online): optimistic.
        assertEquals(SyncState.Connected, SyncMonitor.state(paired = true, syncing = false, online = true, lastOk = null))
    }

    @Test
    fun invitesNormalizeFromBothLinkShapes() {
        assertEquals("zync://pair?h=a&k=b", normalizeInvite("zync://pair?h=a&k=b"))
        assertEquals("zync://pair?h=a&k=b", normalizeInvite("  https://dev.choosh.ai/pair/open?h=a&k=b "))
        assertNull(normalizeInvite("https://dev.choosh.ai/settings/pairing"))
        assertNull(normalizeInvite(""))
    }
}
