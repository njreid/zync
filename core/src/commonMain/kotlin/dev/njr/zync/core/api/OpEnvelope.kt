package dev.njr.zync.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The external-op-api wire contract (spec 2026-07-22-external-op-api.md §3). One flexible
 * [OpIntent] type covers every verb; the server translates each into provenance-tagged
 * ops (`Actor.Bot(id)`). Shared by the server and the Kotlin SDK; the Go SDK mirrors it.
 */
@Serializable
data class OpEnvelope(
    /** Client-generated key; a retry with the same key returns the original result (idempotent). */
    val idempotencyKey: String? = null,
    /** "commit" | "propose"; clamped by the bot's capability. Null = the bot's default. */
    val mode: String? = null,
    val intents: List<OpIntent> = emptyList(),
)

/**
 * One intent. `op` selects the verb; the rest are the (verb-specific) arguments.
 * `parent`/`target`/`context` accept a ULID or the aliases "inbox" / "reference".
 */
@Serializable
data class OpIntent(
    val op: String, // create | comment | setField | addTag | move | complete | trash | attach
    val kind: String? = null, // create: "task" (default) | "project"
    val title: String? = null,
    val parent: String? = null,
    val target: String? = null,
    val field: String? = null,
    val value: JsonElement? = null,
    val text: String? = null,
    val context: String? = null,
    val tags: List<String>? = null,
    val fields: Map<String, JsonElement>? = null,
    val blobRef: String? = null, // attach: content-addressed key from PUT /api/blobs
    val type: String? = null, // attach: attachment type
    val name: String? = null, // attach: filename
)

/** Per-envelope response: one [IntentResult] per intent, in order. */
@Serializable
data class EnvelopeResult(val results: List<IntentResult> = emptyList())

@Serializable
data class IntentResult(
    val op: String,
    /** The affected/created node id, when applicable. */
    val nodeId: String? = null,
    /** "committed" | "proposed" | "error". */
    val status: String,
    val error: String? = null,
)
