package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto-file-done end-to-end (GTD triage §7, RESOLVED Q5 = operator proposes a Reference
 * filing home for a DONE task; human accepts). Deterministic keyword retrieval, no LLM.
 */
class AutoFileDoneEndToEndTest {
    private fun harness() = OperatorHarness(
        operators = listOf(OperatorManifests.autoFileDone()),
        completersFor = { store -> mapOf("auto-file-done" to AutoFileCompletionSource(ReferenceIndex(store))) },
    )

    @Test
    fun doneTaskGetsAReferenceProposal() {
        val h = harness()
        val area = id(1)
        val task = id(2)
        // A Reference area under the well-known root.
        h.push(
            listOf(
                h.ops.setField(area, Fields.KIND, str("project"), hlc(5)),
                h.ops.setField(area, Fields.TITLE, str("Tax Receipts"), hlc(5, 1)),
                h.ops.move(area, WellKnownNodes.REFERENCE_ROOT, hlc(5, 2)),
            ),
        )
        h.push(h.captureTask(task, at = 10, title = "Annual tax receipts"))
        // Nothing proposed while ACTIVE.
        assertTrue(h.operatorOps().none { it is Op.SetField && it.field == Fields.PROPOSED_FILE_PARENT })

        // Mark DONE → triggers auto-file-done.
        h.push(listOf(h.ops.setField(task, Fields.STATUS, str("DONE"), hlc(20))))
        val emitted = h.operatorOps().filterIsInstance<Op.SetField>().filter { it.field == Fields.PROPOSED_FILE_PARENT }
        assertEquals(1, emitted.size)
        assertEquals(Actor.Operator("auto-file-done"), emitted.single().actor)
        assertEquals(area.toString(), emitted.single().value.jsonPrimitive.content)
    }

    @Test
    fun noReferenceMatchProposesNothing() {
        val h = harness()
        val task = id(2)
        h.push(h.captureTask(task, at = 10, title = "Some unrelated done task"))
        h.push(listOf(h.ops.setField(task, Fields.STATUS, str("DONE"), hlc(20))))
        // No reference areas exist → no proposal field written.
        assertTrue(h.operatorOps().none { it is Op.SetField && it.field == Fields.PROPOSED_FILE_PARENT })
    }
}
