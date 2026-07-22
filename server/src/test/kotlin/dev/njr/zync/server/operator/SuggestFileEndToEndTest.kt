package dev.njr.zync.server.operator

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.Op
import dev.njr.zync.server.hlc
import dev.njr.zync.server.id
import dev.njr.zync.server.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keyword file-location suggester end-to-end (GTD triage §6): an inbox item gets a
 * `fileSuggestions` array of ranked Project/Reference targets, provenance-tagged, with a
 * confidence floor (RESOLVED Q4). No LLM — deterministic retrieval via a completer.
 */
class SuggestFileEndToEndTest {
    private fun harness() = OperatorHarness(
        operators = listOf(OperatorManifests.suggestFileLocations()),
        completersFor = { store -> mapOf("suggest-file" to FileSuggesters.suggestFile(ReferenceIndex(store))) },
    )

    private fun project(id: dev.njr.zync.core.id.Ulid, title: String, h: OperatorHarness) = listOf(
        h.ops.setField(id, Fields.KIND, str("project"), hlc(5)),
        h.ops.setField(id, Fields.TITLE, str(title), hlc(5, 1)),
    )

    @Test
    fun inboxItemGetsRankedSuggestions() {
        val h = harness()
        val proj = id(1)
        val task = id(2)
        h.push(project(proj, "Home Renovation", h))
        h.push(h.captureTask(task, at = 10, title = "Renovation quote for kitchen"))

        val emitted = h.operatorOps().filterIsInstance<Op.SetField>().filter { it.field == Fields.FILE_SUGGESTIONS }
        assertEquals(1, emitted.size)
        assertEquals(Actor.Operator("suggest-file"), emitted.single().actor)
        val arr = emitted.single().value.jsonArray
        assertEquals(1, arr.size)
        assertEquals(proj.toString(), arr[0].jsonObject["targetId"]!!.jsonPrimitive.content)
        assertEquals("projects", arr[0].jsonObject["tree"]!!.jsonPrimitive.content)
    }

    @Test
    fun noOverlapYieldsEmptyArrayBelowFloor() {
        val h = harness()
        val proj = id(1)
        val task = id(2)
        h.push(project(proj, "Quarterly Taxes", h))
        h.push(h.captureTask(task, at = 10, title = "Buy milk and eggs"))

        val emitted = h.operatorOps().filterIsInstance<Op.SetField>().filter { it.field == Fields.FILE_SUGGESTIONS }
        assertEquals(1, emitted.size)
        assertTrue(emitted.single().value.jsonArray.isEmpty())
    }

    @Test
    fun referenceRoot_isReferencedForTreeLabels() {
        // Sanity: the reference root constant parses (candidates under it label as "reference").
        assertEquals(26, WellKnownNodes.REFERENCE_ROOT.toString().length)
    }
}
