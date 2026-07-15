package dev.njr.zync.launcher

import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager

/** One launchable app in the search overlay. */
data class AppEntry(val label: String, val packageName: String, val activityName: String) {
    /** Launch intent for this entry (explicit component, new task). */
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(packageName, activityName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * The swipe-left search surface's data (launcher spec L3): every launchable activity
 * (zync's app drawer), plus a web-search handoff. Requires the `<queries>` launcher
 * intent declaration in the manifest for package visibility (API 30+).
 */
object AppSearch {
    fun launchableApps(pm: PackageManager): List<AppEntry> {
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

    /** Hand the query to the default web-search agent. */
    fun webSearch(query: String): Intent = Intent(Intent.ACTION_WEB_SEARCH)
        .putExtra(SearchManager.QUERY, query)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
