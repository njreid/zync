package dev.njr.zync.launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.UserManager
import android.provider.Settings

/** One launchable app (any profile) in the search overlay / bar submenus. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    /** UserManager serial; null = the main profile. Work-profile apps carry theirs. */
    val userSerial: Long? = null,
) {
    /** Launch intent for MAIN-profile entries (work-profile entries go via [AppLaunch]). */
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(packageName, activityName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** Cross-profile launching + icon loading (LauncherApps handles the work profile). */
object AppLaunch {
    fun launch(context: Context, entry: AppEntry): Boolean = runCatching {
        val serial = entry.userSerial
        if (serial == null) {
            context.startActivity(entry.launchIntent())
        } else {
            val um = context.getSystemService(UserManager::class.java)
            val la = context.getSystemService(LauncherApps::class.java)
            val user = um.getUserForSerialNumber(serial) ?: return false
            la.startMainActivity(
                android.content.ComponentName(entry.packageName, entry.activityName),
                user, null, null,
            )
        }
        true
    }.getOrDefault(false)

    fun icon(context: Context, packageName: String, activityName: String, userSerial: Long?): Drawable? =
        runCatching {
            val la = context.getSystemService(LauncherApps::class.java)
            val um = context.getSystemService(UserManager::class.java)
            val user = userSerial?.let { um.getUserForSerialNumber(it) } ?: android.os.Process.myUserHandle()
            la.getActivityList(packageName, user)
                .firstOrNull { it.componentName.className == activityName }
                ?.getBadgedIcon(0)
        }.getOrNull()
}

/** A searchable system-settings destination. */
data class SettingsEntry(val label: String, val action: String) {
    fun launchIntent(): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The search surface's data (launcher spec L3): every launchable activity across
 * ALL profiles (work apps included, badge-iconed) + common settings destinations +
 * a web-search handoff. App names cached in-process ([warm] at app start).
 */
object AppSearch {
    @Volatile private var cachedApps: List<AppEntry>? = null

    /** Pre-load the app list off the main thread (called from ZyncApp.onCreate). */
    fun warm(context: Context) {
        Thread { launchableApps(context) }.apply { name = "zync-appsearch-warm"; isDaemon = true; start() }
    }

    fun launchableApps(context: Context): List<AppEntry> =
        cachedApps ?: queryApps(context).also { cachedApps = it }

    private fun queryApps(context: Context): List<AppEntry> = runCatching {
        val la = context.getSystemService(LauncherApps::class.java)
        val um = context.getSystemService(UserManager::class.java)
        val me = android.os.Process.myUserHandle()
        um.userProfiles.flatMap { user ->
            la.getActivityList(null, user).map { info ->
                AppEntry(
                    label = info.label.toString(),
                    packageName = info.componentName.packageName,
                    activityName = info.componentName.className,
                    userSerial = if (user == me) null else um.getSerialNumberForUser(user),
                )
            }
        }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /** Case-insensitive substring filter; blank query = the full drawer. */
    fun filter(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        // Substring OR word-initials ("gc" → Google Calendar) — prefix hits rank first.
        val matches = apps.filter { q in it.label.lowercase() || initials(it.label).startsWith(q) }
        return matches.sortedBy { if (it.label.lowercase().startsWith(q) || initials(it.label).startsWith(q)) 0 else 1 }
    }

    private fun initials(label: String): String =
        label.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.first().lowercase() }

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
        SettingsEntry("Accessibility settings", Settings.ACTION_ACCESSIBILITY_SETTINGS),
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
