package dev.njr.zync.launcher

import android.content.Context
import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One app pinned to a bar slot's submenu. */
@Serializable
data class BarApp(val label: String, val packageName: String, val activityName: String) {
    fun launchIntent(): Intent = AppEntry(label, packageName, activityName).launchIntent()
}

/** The two configurable bar slots (native settings screen). */
enum class BarRole(val key: String, val title: String) {
    Messages("bar_messages", "Messages"),
    Calendar("bar_calendar", "Calendar"),
}

/**
 * Per-slot app lists for the action bar (device feedback 2026-07-16): the FIRST app
 * is the primary (plain tap); the rest are the long-press → slide → release submenu.
 * Stored as JSON in prefs; empty = fall back to the role-selector intents.
 */
object BarApps {
    private const val PREFS = "zync_launcher"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context, role: BarRole): List<BarApp> =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(role.key, null)
                ?.let { json.decodeFromString<List<BarApp>>(it) }
        }.getOrNull() ?: emptyList()

    fun save(context: Context, role: BarRole, apps: List<BarApp>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(role.key, json.encodeToString(apps)).apply()
    }

    /** The primary (tap) target, or null when unconfigured (→ role-selector intent). */
    fun primary(context: Context, role: BarRole): BarApp? = load(context, role).firstOrNull()
}
