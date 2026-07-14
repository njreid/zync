package dev.njr.zync.server.operator

/**
 * Deterministic [LlmClient] for tests: replies come from an explicit queue,
 * falling back to a programmable default; every request is recorded.
 */
class FakeLlmClient(
    var default: (LlmRequest) -> LlmReply = { LlmReply.Text("""{"summary":"auto"}""") },
) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    private val queued = ArrayDeque<LlmReply>()

    fun enqueue(vararg replies: LlmReply) {
        queued.addAll(replies)
    }

    fun enqueueText(vararg texts: String) {
        texts.forEach { queued.add(LlmReply.Text(it)) }
    }

    override fun complete(request: LlmRequest): LlmReply {
        requests += request
        return queued.removeFirstOrNull() ?: default(request)
    }
}
