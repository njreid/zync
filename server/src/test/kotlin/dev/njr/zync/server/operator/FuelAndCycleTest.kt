package dev.njr.zync.server.operator

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.operator.FieldType
import dev.njr.zync.core.operator.Fuel
import dev.njr.zync.core.operator.OutputSchema
import dev.njr.zync.core.operator.TriggerKind
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.id
import dev.njr.zync.server.sync.SyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fuel budgets and declared-scope cycle detection (spec §7: termination). */
class FuelAndCycleTest {
    private val twoFieldOutput = OutputSchema(mapOf("a" to FieldType.String, "b" to FieldType.String))

    @Test
    fun firing_over_maxOpsPerFiring_is_halted_and_flagged() {
        val h = OperatorHarness(
            listOf(
                manifest(
                    "greedy",
                    writes = setOf("a", "b"),
                    output = twoFieldOutput,
                    fuel = Fuel(maxOpsPerFiring = 1, maxOpsPerCascade = 16),
                ),
            ),
        )
        h.llm.default = { LlmReply.Text("""{"a":"1","b":"2"}""") }

        h.push(h.captureTask(id(1), at = 10))

        assertTrue(h.operatorOps().isEmpty())
        assertEquals(listOf("fuel_exhausted"), h.runStatuses("greedy", id(1)))
    }

    @Test
    fun cascade_fuel_halts_the_operator_for_the_rest_of_the_cascade() {
        val h = OperatorHarness(
            listOf(
                manifest(
                    "greedy",
                    writes = setOf("a", "b"),
                    output = twoFieldOutput,
                    fuel = Fuel(maxOpsPerFiring = 2, maxOpsPerCascade = 3),
                ),
            ),
        )
        h.llm.default = { LlmReply.Text("""{"a":"1","b":"2"}""") }
        val tasks = listOf(id(1), id(2), id(3))

        // Three inbox tasks in one push = one cascade with three firings.
        h.push(tasks.flatMapIndexed { i, task -> h.captureTask(task, at = 10L + i * 10, title = "t$i") })

        // First firing spends 2 of 3; the second would exceed the cascade budget
        // and halts the operator; the third is never evaluated.
        assertEquals(2, h.operatorOps().size)
        assertEquals(2, h.llm.requests.size)
        assertEquals(listOf("done"), h.runStatuses("greedy", tasks[0]))
        assertEquals(listOf("fuel_exhausted"), h.runStatuses("greedy", tasks[1]))
        assertTrue(h.runStatuses("greedy", tasks[2]).isEmpty())
    }

    @Test
    fun operators_chain_within_one_cascade() {
        val hasSummary = ReadScope("has-summary", reads = setOf("summary")) { s ->
            s.alive && "summary" in s.fields
        }
        val h = OperatorHarness(
            operators = listOf(
                manifest("summarize", trigger = TriggerKind.EntityEntersScope),
                manifest(
                    "digest",
                    scopeRef = "has-summary",
                    trigger = TriggerKind.EntityChangesInScope,
                    writes = setOf("digest"),
                    output = OutputSchema(mapOf("digest" to FieldType.String)),
                ),
            ),
            scopes = ReadScopeResolver(listOf(ReadScopes.inboxTask, hasSummary)),
        )
        h.llm.default = { request ->
            when (request.operatorId) {
                "summarize" -> LlmReply.Text("""{"summary":"s"}""")
                else -> LlmReply.Text("""{"digest":"d"}""")
            }
        }

        h.push(h.captureTask(id(1), at = 10))

        val emitted = h.operatorOps().filterIsInstance<Op.SetField>()
        assertEquals(setOf("summary", "digest"), emitted.map { it.field }.toSet())
        assertEquals(Actor.Operator("digest"), emitted.first { it.field == "digest" }.actor)
        assertEquals(listOf("done"), h.runStatuses("digest", id(1)))
    }

    @Test
    fun cyclic_configuration_is_rejected_at_construction() {
        assertFailsWith<IllegalArgumentException> {
            runtimeWith(
                manifest("a", scopeRef = "reads-x", writes = setOf("y"), trigger = TriggerKind.EntityChangesInScope),
                manifest("b", scopeRef = "reads-y", writes = setOf("x"), trigger = TriggerKind.EntityChangesInScope),
            )
        }
    }

    @Test
    fun cycle_through_an_enters_scope_operator_is_permitted() {
        // b can only ever fire once per entity, so the loop cannot sustain itself.
        runtimeWith(
            manifest("a", scopeRef = "reads-x", writes = setOf("y"), trigger = TriggerKind.EntityChangesInScope),
            manifest("b", scopeRef = "reads-y", writes = setOf("x"), trigger = TriggerKind.EntityEntersScope),
        )
    }

    @Test
    fun operator_writing_its_own_read_fields_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            runtimeWith(manifest("navel", scopeRef = "reads-x", writes = setOf("x")))
        }
    }

    private fun runtimeWith(vararg manifests: dev.njr.zync.core.operator.OperatorManifest): OperatorRuntime {
        val db = JvmZyncDatabase.inMemory()
        val service = SyncService(db)
        return OperatorRuntime(
            db = db,
            store = service.stateStore,
            operators = manifests.toList(),
            scopes = ReadScopeResolver(
                listOf(
                    ReadScope("reads-x", reads = setOf("x")) { true },
                    ReadScope("reads-y", reads = setOf("y")) { true },
                ),
            ),
            llm = FakeLlmClient(),
            emit = service::ingestLocal,
            clock = Clock { 0L },
        )
    }
}
