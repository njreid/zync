package dev.njr.zync.web

import dev.njr.zync.core.content.Fields
import dev.njr.zync.core.content.Size
import dev.njr.zync.core.content.Status
import dev.njr.zync.core.content.WellKnownNodes
import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Increment A: the shared GTD vocab base — NodeView surfaces size / fileSuggestions /
 * proposedFileParent, and setSize / file() write the expected ops. (Nothing writes
 * fileSuggestions/proposedFileParent yet — those land with build #6 — so the read
 * path is verified against hand-written fields.)
 */
class GtdVocabTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
    private val read = ContentReadModel(store)

    @Test
    fun setSizeSurfacesOnNodeView() {
        val t = commands.createTask("size me")
        commands.setSize(t, Size.M)
        assertEquals("M", read.node(t)!!.size)
    }

    @Test
    fun fileMovesUnderReferenceRootAndMarksFiled() {
        val t = commands.createTask("archive me")
        commands.file(t)
        val v = read.node(t)!!
        assertEquals(Status.FILED, v.status)
        assertEquals(WellKnownNodes.REFERENCE_ROOT, v.parent)
    }

    @Test
    fun fileSuggestionsParseFromOperatorField() {
        val t = commands.createTask("triage me")
        val target = commands.createProject("Home Reno")
        emitter.setField(
            t, Fields.FILE_SUGGESTIONS,
            kotlinx.serialization.json.buildJsonArray {
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("targetId", JsonPrimitive(target.toString()))
                        put("title", JsonPrimitive("Home Reno"))
                        put("tree", JsonPrimitive("projects"))
                        put("score", JsonPrimitive(0.82))
                    },
                )
            },
        )
        val sugg = read.node(t)!!.fileSuggestions
        assertEquals(1, sugg.size)
        assertEquals(target, sugg[0].targetId)
        assertEquals("projects", sugg[0].tree)
        assertTrue(sugg[0].score > 0.8)
    }

    @Test
    fun malformedFileSuggestionsDegradeToEmpty() {
        val t = commands.createTask("bad data")
        emitter.setField(t, Fields.FILE_SUGGESTIONS, JsonPrimitive("not-an-array"))
        assertTrue(read.node(t)!!.fileSuggestions.isEmpty())
    }

    @Test
    fun proposedFileParentParses() {
        val t = commands.createTask("done task")
        val area = commands.createProject("Receipts")
        emitter.setField(t, Fields.PROPOSED_FILE_PARENT, JsonPrimitive(area.toString()))
        assertEquals(area, read.node(t)!!.proposedFileParent)
    }
}
