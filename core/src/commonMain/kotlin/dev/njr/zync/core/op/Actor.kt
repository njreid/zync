package dev.njr.zync.core.op

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provenance carried on every op (spec §0 invariant 4). Drives field-ownership,
 * operator loop-breaking, and audit. [Operator], [Agent], and [Bot] carry the id of the
 * runtime/identity that authored the op.
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

    /**
     * An external bot/script/integration writing through the op API (external-op-api spec).
     * A [Bot]-authored register value never overwrites a [Human] one (merge rule in
     * [dev.njr.zync.core.merge.apply]), so a bot can propose but never silently clobber a
     * human decision. [id] is the bot's registered id.
     */
    @Serializable
    @SerialName("bot")
    data class Bot(val id: String) : Actor
}
