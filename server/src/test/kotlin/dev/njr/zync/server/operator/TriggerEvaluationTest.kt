package dev.njr.zync.server.operator

import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trigger evaluation over the ingested op stream (spec §7). */
class TriggerEvaluationTest {
    private val clarify = OperatorManifests.autoClarifyInbox()

    @Test
    fun task_entering_the_inbox_fires_the_operator() {
        val h = OperatorHarness(listOf(clarify))
        h.llm.default = { LlmReply.Text("""{"summary":"clarified","suggestedContext":"@errands"}""") }
        val task = id(1)

        h.push(h.captureTask(task, at = 10))

        val emitted = h.operatorOps()
        assertEquals(2, emitted.size)
        assertTrue(emitted.all { it.actor == Actor.Operator(clarify.id) })
        assertTrue(emitted.all { it.entityId == task })
        assertEquals(
            setOf("summary", "suggestedContext"),
            emitted.filterIsInstance<Op.SetField>().map { it.field }.toSet(),
        )
    }

    @Test
    fun task_under_a_parent_is_out_of_scope() {
        val h = OperatorHarness(listOf(clarify))
        val task = id(1)
        val parent = id(2)

        h.push(h.captureTask(task, at = 10) + h.ops.move(task, parent, hlc(11)))

        assertTrue(h.operatorOps().isEmpty())
        assertTrue(h.llm.requests.isEmpty())
    }

    @Test
    fun operator_authored_ops_never_self_trigger() {
        val h = OperatorHarness(listOf(clarify))
        val task = id(1)
        val self = Actor.Operator(clarify.id)
        // An in-scope entity created entirely by the operator itself (e.g. replica echo).
        val ops = listOf(
            h.ops.setField(task, "kind", str("task"), hlc(10), actor = self),
            h.ops.setField(task, "title", str("Echo"), hlc(10, 1), actor = self),
            h.ops.setField(task, "status", str("ACTIVE"), hlc(10, 2), actor = self),
        )

        h.push(ops)

        assertTrue(h.llm.requests.isEmpty())
        // Nothing beyond the pushed ops themselves — no clarification was emitted.
        assertEquals(ops.map { it.opId }.toSet(), h.operatorOps().map { it.opId }.toSet())
    }

    @Test
    fun late_op_that_leaves_scope_cancels_the_fire() {
        val h = OperatorHarness(listOf(clarify))
        val task = id(1)
        // Capture and completion land in the same batch: scope is checked against
        // the merged state BEFORE idempotency, so nothing fires.
        h.push(h.captureTask(task, at = 10) + h.ops.setField(task, "status", str("DONE"), hlc(20)))

        assertTrue(h.llm.requests.isEmpty())
        assertTrue(h.operatorOps().isEmpty())
        assertTrue(h.runStatuses(clarify.id, task).isEmpty())
    }

    @Test
    fun ops_on_unread_fields_are_ignored() {
        val h = OperatorHarness(listOf(clarify))
        val task = id(1)
        h.push(h.captureTask(task, at = 10))
        assertEquals(1, h.llm.requests.size)

        // sortOrder is not in the inbox scope's read set: no re-evaluation.
        h.push(listOf(h.ops.setField(task, "sortOrder", str("5"), hlc(30))))
        assertEquals(1, h.llm.requests.size)
    }

    @Test
    fun untrusted_content_is_delimited_in_the_prompt() {
        val h = OperatorHarness(listOf(clarify))
        h.push(h.captureTask(id(1), at = 10, title = "Buy milk"))

        val request = h.llm.requests.single()
        assertTrue(request.user.contains("Buy milk"))
        assertTrue(request.user.startsWith("<entity"))
        assertTrue(request.system.contains("untrusted"))
        assertEquals(clarify.output, request.schema)
    }
}
