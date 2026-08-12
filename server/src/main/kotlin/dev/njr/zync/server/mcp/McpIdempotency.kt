package dev.njr.zync.server.mcp

import kotlinx.serialization.json.JsonObject

/**
 * A bounded `(botId + jsonrpc-id) → tool result` cache so a retried `tools/call` re-proposes
 * nothing and returns its original result (spec 2026-08-11 §5). Stateless transport still needs
 * this small bit of dedup state; it is best-effort (in-memory, LRU) — a restart forgets it, which
 * is safe because the worst case is a duplicate *proposal* a human can dismiss.
 */
class McpIdempotencyCache(private val max: Int = 1024) {
    private val map = object : LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>): Boolean = size > max
    }

    @Synchronized fun get(key: String): JsonObject? = map[key]

    @Synchronized fun put(key: String, value: JsonObject) {
        map[key] = value
    }
}
