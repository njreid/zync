package dev.njr.zync.server.operator

import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typed-output validation with bounded retries (spec §7: "retries: N attempts
 * on schema-validation failure — the ONLY control flow").
 */
class TypedOutputRetryTest {
    @Test
    fun invalid_output_is_retried_until_valid() {
        val h = OperatorHarness(listOf(manifest("clarify", retries = 2)))
        h.llm.enqueueText("not json at all", """["not","an","object"]""", """{"summary":"third time lucky"}""")

        h.push(h.captureTask(id(1), at = 10))

        assertEquals(3, h.llm.requests.size)
        assertEquals(1, h.operatorOps().size)
        assertEquals(listOf("done"), h.runStatuses("clarify", id(1)))
    }

    @Test
    fun rejected_after_retry_budget_and_flagged() {
        val h = OperatorHarness(listOf(manifest("clarify", retries = 1)))
        h.llm.default = { LlmReply.Text("""{"summary": 42}""") } // wrong type, forever

        h.push(h.captureTask(id(1), at = 10))

        assertEquals(2, h.llm.requests.size) // initial + 1 retry, then stop
        assertTrue(h.operatorOps().isEmpty())
        assertEquals(listOf("rejected"), h.runStatuses("clarify", id(1)))
    }

    @Test
    fun rejected_version_is_not_retried_but_a_new_version_is() {
        val h = OperatorHarness(listOf(manifest("clarify", retries = 0)))
        h.llm.enqueueText("garbage")
        val task = id(1)
        val capture = h.captureTask(task, at = 10)

        h.push(capture)
        assertEquals(listOf("rejected"), h.runStatuses("clarify", task))

        // Redelivery at the same version: no new attempt (no hot loop).
        h.redeliver(capture)
        assertEquals(1, h.llm.requests.size)

        // A material edit produces a new version and a fresh attempt.
        h.push(listOf(h.ops.setField(task, "title", str("Renamed"), hlc(30))))
        assertEquals(2, h.llm.requests.size)
        assertEquals(1, h.operatorOps().size)
        assertEquals(setOf("rejected", "done"), h.runStatuses("clarify", task).toSet())
    }

    @Test
    fun refusal_counts_as_a_failed_attempt() {
        val h = OperatorHarness(listOf(manifest("clarify", retries = 1)))
        h.llm.enqueue(LlmReply.Refusal("safety"), LlmReply.Text("""{"summary":"ok"}"""))

        h.push(h.captureTask(id(1), at = 10))

        assertEquals(2, h.llm.requests.size)
        assertEquals(listOf("done"), h.runStatuses("clarify", id(1)))
    }

    @Test
    fun unavailable_llm_aborts_without_recording_so_the_next_trigger_retries() {
        val h = OperatorHarness(listOf(manifest("clarify", retries = 2)))
        h.llm.enqueue(LlmReply.Unavailable("http 529"))
        val task = id(1)
        val capture = h.captureTask(task, at = 10)

        h.push(capture)
        assertEquals(1, h.llm.requests.size)
        assertTrue(h.runStatuses("clarify", task).isEmpty()) // nothing recorded

        // The next delivery retries and succeeds (fake default is valid).
        h.redeliver(capture)
        assertEquals(2, h.llm.requests.size)
        assertEquals(listOf("done"), h.runStatuses("clarify", task))
        assertEquals(1, h.operatorOps().size)
    }
}
