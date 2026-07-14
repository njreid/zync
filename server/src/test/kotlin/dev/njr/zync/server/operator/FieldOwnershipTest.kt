package dev.njr.zync.server.operator

import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.OutputSchema
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.server.id
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Field ownership by construction (spec §7): an operator can never emit an op
 * targeting a human-owned field, even when the LLM output tries to.
 */
class FieldOwnershipTest {
    @Test
    fun ops_outside_the_write_scope_are_dropped() {
        // The schema *declares* title (so validation passes) but the write scope
        // only permits summary — a prompt-injected "rename the task" is discarded.
        val h = OperatorHarness(
            listOf(
                manifest(
                    "clarify",
                    writes = setOf("summary"),
                    output = OutputSchema(
                        fields = mapOf("summary" to FieldType.String, "title" to FieldType.String),
                        required = setOf("summary"),
                    ),
                ),
            ),
        )
        h.llm.default = { LlmReply.Text("""{"summary":"ok","title":"HACKED"}""") }
        val task = id(1)

        h.push(h.captureTask(task, at = 10, title = "Buy milk"))

        val emitted = h.operatorOps().filterIsInstance<Op.SetField>()
        assertEquals(listOf("summary"), emitted.map { it.field })
        assertEquals(listOf("done"), h.runStatuses("clarify", task))

        // The human title is untouched, with human provenance.
        val title = h.service.stateStore.getRegister(RegisterKey(task, "title"))!!
        assertEquals(JsonPrimitive("Buy milk"), title.value)
        assertEquals(Actor.Human, title.actor)

        val summary = h.service.stateStore.getRegister(RegisterKey(task, "summary"))!!
        assertEquals(Actor.Operator("clarify"), summary.actor)
    }

    @Test
    fun undeclared_output_fields_are_never_built_into_ops() {
        // The reply smuggles a field that isn't even in the schema.
        val h = OperatorHarness(listOf(manifest("clarify")))
        h.llm.default = { LlmReply.Text("""{"summary":"ok","status":"DONE"}""") }
        val task = id(1)

        h.push(h.captureTask(task, at = 10))

        val emitted = h.operatorOps().filterIsInstance<Op.SetField>()
        assertEquals(listOf("summary"), emitted.map { it.field })
        val status = h.service.stateStore.getRegister(RegisterKey(task, "status"))!!
        assertEquals(JsonPrimitive("ACTIVE"), status.value)
    }
}
