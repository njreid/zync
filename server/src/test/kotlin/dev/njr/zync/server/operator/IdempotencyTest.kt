package dev.njr.zync.server.operator

import dev.njr.zync.core.operator.TriggerKind
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Idempotency + re-entrancy: a firing is keyed by (operator, entity, input
 * version); redelivery is a no-op, version changes re-fire (spec §7).
 */
class IdempotencyTest {
    @Test
    fun redelivered_trigger_at_the_same_version_is_skipped() {
        val h = OperatorHarness(listOf(manifest("summarize")))
        val capture = h.captureTask(id(1), at = 10)
        h.push(capture)
        assertEquals(1, h.llm.requests.size)

        // The hook fires again for the same ops (duplicate notification).
        h.redeliver(capture)

        assertEquals(1, h.llm.requests.size)
        assertEquals(1, h.operatorOps().size)
        assertEquals(listOf("done"), h.runStatuses("summarize", id(1)))
    }

    @Test
    fun version_change_refires_a_changes_in_scope_operator() {
        val h = OperatorHarness(listOf(manifest("summarize", trigger = TriggerKind.EntityChangesInScope)))
        val task = id(1)
        h.push(h.captureTask(task, at = 10))
        assertEquals(1, h.llm.requests.size)

        // Late in-scope edit to a read field: new input version, re-entrant fire.
        h.push(listOf(h.ops.setField(task, "title", str("Buy oat milk"), hlc(30))))

        assertEquals(2, h.llm.requests.size)
        assertEquals(listOf("done", "done"), h.runStatuses("summarize", task))
    }

    @Test
    fun enters_scope_operator_fires_at_most_once_per_entity() {
        val h = OperatorHarness(listOf(OperatorManifests.autoClarifyInbox()))
        val task = id(1)
        h.push(h.captureTask(task, at = 10))
        assertEquals(1, h.llm.requests.size)

        h.push(listOf(h.ops.setField(task, "title", str("Renamed"), hlc(30))))

        assertEquals(1, h.llm.requests.size)
    }

    @Test
    fun own_writes_do_not_change_the_operators_input_version() {
        val h = OperatorHarness(listOf(manifest("summarize")))
        val capture = h.captureTask(id(1), at = 10)
        h.push(capture)
        assertEquals(1, h.llm.requests.size)
        assertEquals(1, h.operatorOps().size)

        // After the summary landed, redelivering the original trigger must still
        // resolve to the same version (summary is not a read field) and skip.
        h.redeliver(capture)
        assertEquals(1, h.llm.requests.size)
    }
}
