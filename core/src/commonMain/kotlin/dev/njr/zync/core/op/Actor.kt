package dev.njr.zync.core.op

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provenance carried on every op (spec §0 invariant 4). Drives field-ownership,
 * operator loop-breaking, and audit. [Operator] and [Agent] carry the id of the
 * runtime that authored the op.
 */
@Serializable
sealed interface Actor {
    @Serializable
    @SerialName("human")
    data object Human : Actor

    @Serializable
    @SerialName("operator")
    data class Operator(val id: String) : Actor

    @Serializable
    @SerialName("agent")
    data class Agent(val id: String) : Actor
}
