package dev.njr.zync.server.operator

import dev.njr.zync.core.operator.OutputSchema

/**
 * The runtime's LLM port. One call = one completion attempt; the runtime owns
 * the retry loop (bounded by the manifest's `retries`) and the typed-output
 * validation of whatever comes back. Implementations: [AnthropicLlmClient]
 * (production) and the deterministic fake in the test sources.
 */
interface LlmClient {
    fun complete(request: LlmRequest): LlmReply
}

/**
 * One completion attempt. [system] carries the trusted operator instructions;
 * [user] carries the delimited, untrusted entity content (threat model T4:
 * task content is never "instructions"). [schema] is the typed output the
 * reply must satisfy — real clients may pass it through as a structured-output
 * constraint, but the runtime validates regardless.
 */
data class LlmRequest(
    val operatorId: String,
    val system: String,
    val user: String,
    val schema: OutputSchema,
    val maxTokens: Int = 4096,
)

/** The three ways a completion attempt can come back. */
sealed interface LlmReply {
    /** Model text expected (but not trusted) to be a JSON object. */
    data class Text(val text: String) : LlmReply

    /** The model or its safety layer declined; counts as a failed attempt. */
    data class Refusal(val reason: String?) : LlmReply

    /**
     * Transport/service failure (network, 4xx/5xx, overload). The firing is
     * aborted *without* recording a run, so the next trigger retries fresh.
     */
    data class Unavailable(val message: String) : LlmReply
}
