package dev.njr.zync.core.api

import kotlinx.serialization.Serializable

/**
 * A bot's capability grant (external-op-api spec §2): what verbs it may use, whether it
 * writes live or only proposes, which fields it may set, where it may create/move, and its
 * per-verb rate limits. Stored as JSON in the `bot` registry.
 */
@Serializable
data class BotCapabilities(
    /** Verbs this bot may use. */
    val verbs: Set<String> = ALL_VERBS,
    /** "commit" = write live; "propose" = every mutation becomes a proposal for human review. */
    val mode: String = "commit",
    /** setField whitelist; null = any field. */
    val fields: Set<String>? = null,
    /** Root of the area this bot may create/move within; null/"*" = anywhere. (Enforcement TBD.) */
    val subtree: String? = null,
    /** Per-minute limit per verb; the "default" key applies to verbs without their own limit. */
    val rateLimit: Map<String, Int> = emptyMap(),
) {
    fun limitFor(verb: String): Int? = rateLimit[verb] ?: rateLimit["default"]

    companion object {
        val ALL_VERBS = setOf(
            "create", "comment", "setField", "addTag", "move", "complete", "trash", "attach",
            "addFreeTag", "removeFreeTag",
        )
    }
}
