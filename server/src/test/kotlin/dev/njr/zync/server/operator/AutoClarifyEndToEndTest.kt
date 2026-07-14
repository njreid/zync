package dev.njr.zync.server.operator

import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.core.state.RegisterKey
import dev.njr.zync.server.id
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reference operator end-to-end against the fake LLM: human capture ops
 * pushed through the real SyncService trigger auto-clarify-inbox, whose
 * provenance-tagged output lands in the op log, the merged state, and the
 * sync feed.
 */
class AutoClarifyEndToEndTest {
    private val clarify = OperatorManifests.autoClarifyInbox()

    private fun harness(): OperatorHarness {
        val h = OperatorHarness(listOf(clarify))
        h.llm.default = { request ->
            // Derive the reply from the prompt to prove entity content flows through.
            val title = Regex("title: \"([^\"]*)\"").find(request.user)?.groupValues?.get(1) ?: "?"
            LlmReply.Text("""{"summary":"clarified: $title","suggestedContext":"@next"}""")
        }
        return h
    }

    @Test
    fun captured_inbox_task_gets_clarified() {
        val h = harness()
        val task = id(1)
        val headBefore = h.service.bootstrap().headSeq

        h.push(h.captureTask(task, at = 10, title = "Buy milk"))

        // Provenance-tagged ops in the durable log, with transport seq assigned.
        val emitted = h.operatorOps().filterIsInstance<Op.SetField>()
        assertEquals(2, emitted.size)
        assertTrue(emitted.all { it.actor == Actor.Operator("auto-clarify-inbox") })
        assertTrue(emitted.all { it.deviceId == "server" })
        assertTrue(emitted.all { it.seq != null })

        // Merged into state with operator provenance.
        val store = h.service.stateStore
        assertEquals(
            JsonPrimitive("clarified: Buy milk"),
            store.getRegister(RegisterKey(task, "summary"))!!.value,
        )
        assertEquals(
            JsonPrimitive("@next"),
            store.getRegister(RegisterKey(task, "suggestedContext"))!!.value,
        )
        assertEquals(Actor.Operator("auto-clarify-inbox"), store.getRegister(RegisterKey(task, "summary"))!!.actor)

        // Visible on the sync feed, so replicas pull the clarification.
        val pulled = h.service.pull(since = headBefore).ops
        assertTrue(pulled.any { it.actor is Actor.Operator })

        // Recorded as handled.
        assertEquals(listOf("done"), h.runStatuses("auto-clarify-inbox", task))
    }

    @Test
    fun fires_once_per_task_and_again_for_each_new_task() {
        val h = harness()
        val first = id(1)
        val second = id(2)

        h.push(h.captureTask(first, at = 10, title = "Buy milk"))
        h.push(h.captureTask(second, at = 20, title = "Call plumber"))

        assertEquals(2, h.llm.requests.size)
        assertEquals(
            JsonPrimitive("clarified: Call plumber"),
            h.service.stateStore.getRegister(RegisterKey(second, "summary"))!!.value,
        )

        // Re-pushing the same batch is fully deduped: no re-trigger, no dupes.
        h.push(h.captureTask(first, at = 10, title = "Buy milk"))
        assertEquals(2, h.llm.requests.size)
        assertEquals(4, h.operatorOps().size) // 2 tasks x 2 fields
    }

    @Test
    fun operator_output_does_not_retrigger_the_operator() {
        val h = harness()
        h.push(h.captureTask(id(1), at = 10))
        // One firing: the summary/suggestedContext ops it emitted re-entered the
        // ingest path but never came back around as triggers.
        assertEquals(1, h.llm.requests.size)
        assertEquals(2, h.operatorOps().size)
    }
}
