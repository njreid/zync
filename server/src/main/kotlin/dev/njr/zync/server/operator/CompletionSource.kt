package dev.njr.zync.server.operator

import dev.njr.zync.core.state.EntitySnapshot

/**
 * Produces one typed-output attempt for an operator firing (GTD triage §6). The LLM is
 * one implementation ([LlmCompletionSource]); deterministic *retrieval* operators
 * (suggest-file, auto-file-done) are another — both flow through the exact same
 * OperatorRuntime lifecycle (readScope match → idempotency → typed output → write scope
 * → fuel → provenance). Same [LlmReply] contract: Text(json) is validated against the
 * operator's OutputSchema; Unavailable aborts without recording (retries next trigger).
 */
fun interface CompletionSource {
    fun complete(request: LlmRequest, snapshot: EntitySnapshot): LlmReply
}

/** Adapts the existing [LlmClient] (ignores the snapshot). The default for every operator. */
class LlmCompletionSource(private val llm: LlmClient) : CompletionSource {
    override fun complete(request: LlmRequest, snapshot: EntitySnapshot): LlmReply = llm.complete(request)
}
