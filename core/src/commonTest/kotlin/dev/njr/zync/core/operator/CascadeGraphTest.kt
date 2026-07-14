package dev.njr.zync.core.operator

import dev.njr.zync.core.clock.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CascadeGraphTest {
    private fun io(id: String, reads: Set<String>, writes: Set<String>, refireable: Boolean = true) =
        OperatorIo(id, reads, writes, refireable)

    @Test
    fun mutual_feeders_are_a_cycle() {
        val cycle = CascadeGraph.findCycle(
            listOf(io("a", reads = setOf("x"), writes = setOf("y")), io("b", reads = setOf("y"), writes = setOf("x"))),
        )
        assertNotNull(cycle)
        assertTrue(cycle.first() == cycle.last())
        assertTrue("a" in cycle && "b" in cycle)
    }

    @Test
    fun a_chain_is_acyclic() {
        val cycle = CascadeGraph.findCycle(
            listOf(
                io("a", reads = setOf("title"), writes = setOf("summary")),
                io("b", reads = setOf("summary"), writes = setOf("digest")),
            ),
        )
        assertNull(cycle)
    }

    @Test
    fun enters_scope_target_breaks_the_cycle() {
        // b would close the loop, but it fires at most once per entity.
        val cycle = CascadeGraph.findCycle(
            listOf(
                io("a", reads = setOf("x"), writes = setOf("y")),
                io("b", reads = setOf("y"), writes = setOf("x"), refireable = false),
            ),
        )
        assertNull(cycle)
    }

    @Test
    fun self_edges_are_ignored() {
        assertNull(CascadeGraph.findCycle(listOf(io("a", reads = setOf("x"), writes = setOf("x")))))
    }
}

class InputVersionTest {
    @Test
    fun canonical_and_order_independent() {
        val a = InputVersion.of(
            fields = mapOf("title" to Hlc(1, 0, "srv"), "kind" to Hlc(2, 1, "phone")),
            tags = mapOf("ctx" to Hlc(3, 0, "srv")),
            parent = null,
        )
        val b = InputVersion.of(
            fields = mapOf("kind" to Hlc(2, 1, "phone"), "title" to Hlc(1, 0, "srv")),
            tags = mapOf("ctx" to Hlc(3, 0, "srv")),
            parent = null,
        )
        assertEquals(a, b)
        assertEquals("f:kind=2:1:phone|f:title=1:0:srv|t:ctx=3:0:srv|p:-", a)
    }

    @Test
    fun any_read_input_changes_the_version() {
        val base = InputVersion.of(mapOf("title" to Hlc(1, 0, "srv")), emptyMap(), null)
        assertTrue(base != InputVersion.of(mapOf("title" to Hlc(1, 1, "srv")), emptyMap(), null))
        assertTrue(base != InputVersion.of(mapOf("title" to Hlc(1, 0, "srv")), mapOf("c" to Hlc(1, 0, "srv")), null))
        assertTrue(base != InputVersion.of(mapOf("title" to Hlc(1, 0, "srv")), emptyMap(), "parent"))
    }
}
