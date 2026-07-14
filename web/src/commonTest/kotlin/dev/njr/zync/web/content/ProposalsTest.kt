package dev.njr.zync.web.content

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.agent.AgentTaskStatus
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.state.InMemoryStateStore
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The proposed-objects-back flow (spec §8) at the read-model/commands level: agent
 * proposals are quarantined from normal task/comment lists until a human accepts,
 * and agent-flow machinery kinds never render as GTD tasks. The ops here stand in
 * for the M9 agent runtime — same fields, same flag.
 */
class ProposalsTest {
    private val store = InMemoryStateStore()
    private val emitter = RecordingEmitter(store)
    private val commands = ContentCommands(emitter)
    private val read = ContentReadModel(store)

    private fun proposalComment(parent: Ulid, text: String): Ulid {
        val id = emitter.newId()
        emitter.setField(id, "kind", JsonPrimitive("comment"))
        emitter.setField(id, "title", JsonPrimitive(text))
        emitter.setField(id, AgentFlow.FIELD_PROPOSED, JsonPrimitive(true))
        emitter.move(id, parent)
        return id
    }

    @Test
    fun proposalIsQuarantinedUntilAcceptedThenBecomesOrdinaryContent() {
        val task = commands.createTask("Plan trip")
        val proposal = proposalComment(task, "Proposed: book flights first")

        assertEquals(listOf(proposal), read.proposals().map { it.id }, "surfaces in the review panel")
        assertTrue(read.comments(task).none { it.id == proposal }, "hidden from comments while proposed")
        assertTrue(read.children(null).none { it.id == proposal }, "never a task row")

        commands.acceptProposal(proposal)

        assertTrue(read.proposals().isEmpty(), "accepted → leaves the review panel")
        assertEquals(listOf(proposal), read.comments(task).map { it.id }, "accepted → ordinary comment")
    }

    @Test
    fun rejectedProposalLeavesEverything() {
        val task = commands.createTask("Plan trip")
        val proposal = proposalComment(task, "Proposed: charter a zeppelin")

        commands.rejectProposal(proposal)

        assertTrue(read.proposals().isEmpty())
        assertTrue(read.comments(task).isEmpty())
        // Reversible trash, not a tombstone: the node still exists, just DROPPED.
        assertEquals("DROPPED", read.node(proposal)?.status)
    }

    @Test
    fun agentFlowKindsNeverRenderAsTasks() {
        for (kind in AgentFlow.INTERNAL_KINDS) {
            val id = emitter.newId()
            emitter.setField(id, "kind", JsonPrimitive(kind))
            emitter.setField(id, "title", JsonPrimitive("machinery: $kind"))
        }
        assertTrue(read.children(null).isEmpty(), "recommendation/agent_task are not task rows")
        assertTrue(read.inbox(null).isEmpty())
    }

    @Test
    fun agentTaskStatusParsesStrictly() {
        assertEquals(AgentTaskStatus.RUNNING, AgentTaskStatus.of("RUNNING"))
        assertNull(AgentTaskStatus.of("running"), "unknown/miscased values are corrupt, not defaulted")
        assertNull(AgentTaskStatus.of(null))
    }
}
