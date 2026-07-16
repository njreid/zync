package dev.njr.zync.launcher

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A previously selected search result (app, settings page, or web search). */
@Serializable
data class RecentItem(
    val kind: Kind,
    val label: String,
    val packageName: String? = null,
    val activityName: String? = null,
    val userSerial: Long? = null,
    val settingsAction: String? = null,
    val webQuery: String? = null,
) {
    enum class Kind { App, Setting, Web }
}

/**
 * Search recents (device feedback 2026-07-16): the five most recently selected
 * results head the blank-query list; the five most recent query texts show when the
 * field is focused. Plain prefs JSON.
 */
object SearchHistory {
    private const val PREFS = "zync_launcher"
    private const val ITEMS = "search_recent_items"
    private const val QUERIES = "search_recent_queries"
    private val json = Json { ignoreUnknownKeys = true }

    fun recentItems(context: Context): List<RecentItem> =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, null)
                ?.let { json.decodeFromString<List<RecentItem>>(it) }
        }.getOrNull() ?: emptyList()

    fun recentQueries(context: Context): List<String> =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(QUERIES, null)
                ?.let { json.decodeFromString<List<String>>(it) }
        }.getOrNull() ?: emptyList()

    fun recordItem(context: Context, item: RecentItem) {
        val next = (listOf(item) + recentItems(context).filterNot { it.dedupeKey() == item.dedupeKey() }).take(5)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ITEMS, json.encodeToString(next)).apply()
    }

    fun recordQuery(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = (listOf(q) + recentQueries(context).filterNot { it.equals(q, ignoreCase = true) }).take(5)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(QUERIES, json.encodeToString(next)).apply()
    }

    private fun RecentItem.dedupeKey(): String =
        "$kind|$packageName|$activityName|$settingsAction|${webQuery?.lowercase()}"
}
