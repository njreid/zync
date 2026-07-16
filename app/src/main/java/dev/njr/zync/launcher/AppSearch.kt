package dev.njr.zync.launcher

import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** One launchable app in the search overlay. */
data class AppEntry(val label: String, val packageName: String, val activityName: String) {
    /** Launch intent for this entry (explicit component, new task). */
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(packageName, activityName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** A searchable system-settings destination. */
data class SettingsEntry(val label: String, val action: String) {
    fun launchIntent(): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The swipe-left search surface's data (launcher spec L3): every launchable activity
 * (zync's app drawer) + common settings destinations + a web-search handoff.
 * App names are cached in-process so the overlay opens instantly ([warm] at app
 * start); package churn is rare enough that staleness until next process is fine.
 * Requires the `<queries>` launcher intent declaration for package visibility.
 */
object AppSearch {
    @Volatile private var cachedApps: List<AppEntry>? = null

    /** Pre-load the app list off the main thread (called from ZyncApp.onCreate). */
    fun warm(pm: PackageManager) {
        Thread { launchableApps(pm) }.apply { name = "zync-appsearch-warm"; isDaemon = true; start() }
    }

    fun launchableApps(pm: PackageManager): List<AppEntry> =
        cachedApps ?: queryApps(pm).also { cachedApps = it }

    private fun queryApps(pm: PackageManager): List<AppEntry> {
        val launchables = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchables, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.label.lowercase() }
    }

    /** Case-insensitive substring filter; blank query = the full drawer. */
    fun filter(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter { q in it.label.lowercase() }
    }

    /** Settings destinations matching [query]; hidden when the query is blank. */
    fun settingsMatches(query: String): List<SettingsEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return SETTINGS.filter { q in it.label.lowercase() }
    }

    /** Show the web handoff when few local results match (device feedback: ≤ 5). */
    fun showWebSearch(query: String, matchCount: Int): Boolean =
        query.isNotBlank() && matchCount <= 5

    /** Hand the query to the default web-search agent. */
    fun webSearch(query: String): Intent = Intent(Intent.ACTION_WEB_SEARCH)
        .putExtra(SearchManager.QUERY, query)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Common settings surfaces, searchable by name. */
    val SETTINGS: List<SettingsEntry> = listOf(
        SettingsEntry("Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS),
        SettingsEntry("Bluetooth settings", Settings.ACTION_BLUETOOTH_SETTINGS),
        SettingsEntry("Display settings", Settings.ACTION_DISPLAY_SETTINGS),
        SettingsEntry("Sound settings", Settings.ACTION_SOUND_SETTINGS),
        SettingsEntry("Battery settings", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        SettingsEntry("Storage settings", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        SettingsEntry("Apps settings", Settings.ACTION_APPLICATION_SETTINGS),
        SettingsEntry("Network settings", Settings.ACTION_WIRELESS_SETTINGS),
        SettingsEntry("Date and time settings", Settings.ACTION_DATE_SETTINGS),
        SettingsEntry("Security settings", Settings.ACTION_SECURITY_SETTINGS),
        SettingsEntry("Location settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SettingsEntry("Default apps settings", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        SettingsEntry("System settings", Settings.ACTION_SETTINGS),
    )
}
