package dev.njr.zync.core.content

import dev.njr.zync.core.id.Ulid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WellKnownNodesTest {
    @Test
    fun referenceRootIsAStableParseableUlid() {
        val root = WellKnownNodes.REFERENCE_ROOT
        // Round-trips through the ULID codec (canonical, 26 chars).
        assertEquals(root, Ulid.parse(root.toString()))
        assertEquals(26, root.toString().length)
    }

    @Test
    fun statusAndSizeVocabHoldExpectedValues() {
        assertEquals(listOf("S", "M", "L"), Size.ALL)
        assertTrue(Status.FILED == "FILED" && Status.WAITING == "WAITING")
    }
}
