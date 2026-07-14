package dev.njr.zync.core.agent

/**
 * Shared vocabulary of the human-gated agent flow (op/merge spec §8). The flow is
 * ordinary nodes + fields in the op log — no new op types — so this contract is just
 * the kind/field names three parties agree on: the operator runtime (emits
 * recommendation nodes), the agent runtime (emits proposed nodes, M9 design doc),
 * and the `:web` review surface (accepts/rejects proposals as human ops).
 */
object AgentFlow {
    /** An operator's "run an agent on this" suggestion; approval mints an agent task. */
    const val KIND_RECOMMENDATION = "recommendation"

    /** A human-approved agent run; its `status` field holds an [AgentTaskStatus] name. */
    const val KIND_AGENT_TASK = "agent_task"

    /** Boolean field flagging an agent-authored node as awaiting human review. */
    const val FIELD_PROPOSED = "proposed"

    /** Which agent an agent task / recommendation runs. */
    const val FIELD_AGENT_KIND = "agentKind"

    /** The node an agent task / recommendation targets. */
    const val FIELD_SUBJECT_ID = "subjectId"

    /** Back-reference from an agent task to the recommendation it approves. */
    const val FIELD_RECOMMENDATION_ID = "recommendationId"

    /** Stamped on every proposal so a bad run can be grouped and bulk-rejected. */
    const val FIELD_AGENT_TASK_ID = "agentTaskId"

    /** Node kinds that are flow machinery, never rendered as GTD tasks. */
    val INTERNAL_KINDS = setOf(KIND_RECOMMENDATION, KIND_AGENT_TASK)
}

/** Lifecycle of an agent task node's `status` field (agent-owned after creation). */
enum class AgentTaskStatus {
    PENDING, RUNNING, DONE, FAILED, CANCELLED;

    companion object {
        /** Parse a raw field value; null for absent/unknown (treat as corrupt, not PENDING). */
        fun of(value: String?): AgentTaskStatus? = entries.firstOrNull { it.name == value }
    }
}
