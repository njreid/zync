package dev.njr.zync.launcher

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * The bar's center slot (context-specific app) + the right-swipe app.
 *
 * Explicit picks live in prefs; unset slots fall back to package-based defaults
 * resolved against the installed-app cache (@town → Maps, @home → Google Home,
 * @work → the WORK-profile Drive; swipe → Harmonic). A default that isn't
 * installed resolves to null and the slot invites configuration instead.
 */
object ContextApps {
    private const val PREFS = "zync_launcher"
    private const val CONTEXT_KEY = "bar_context_apps" // JSON Map<"@name", BarApp>
    private const val SWIPE_KEY = "bar_swipe_app" // JSON BarApp
    private val json = Json { ignoreUnknownKeys = true }

    private data class Default(val packageName: String, val workProfile: Boolean = false)

    private val DEFAULTS = mapOf(
        "@town" to Default("com.google.android.apps.maps"),
        "@home" to Default("com.google.android.apps.chromecast.app"),
        "@work" to Default("com.google.android.apps.docs", workProfile = true),
    )
    private val SWIPE_DEFAULT = Default("com.simon.harmonichackernews")

    /** The app for a context (with or without the `@`): explicit pick, else default. */
    fun pick(context: Context, contextName: String?): BarApp? {
        val name = normalize(contextName) ?: return null
        explicit(context)[name]?.let { return it }
        return DEFAULTS[name]?.let { resolveDefault(context, it) }
    }

    /** Explicit (user-chosen) per-context picks. */
    fun explicit(context: Context): Map<String, BarApp> =
        runCatching {
            prefs(context).getString(CONTEXT_KEY, null)?.let { json.decodeFromString<Map<String, BarApp>>(it) }
        }.getOrNull() ?: emptyMap()

    /** Set (or with null, clear back to default) a context's app. */
    fun save(context: Context, contextName: String, app: BarApp?) {
        val name = normalize(contextName) ?: return
        val next = explicit(context).toMutableMap()
        if (app == null) next.remove(name) else next[name] = app
        prefs(context).edit().putString(CONTEXT_KEY, json.encodeToString(next.toMap())).apply()
    }

    /** What a rightward-origin ("right") swipe launches: explicit pick, else Harmonic. */
    fun swipeApp(context: Context): BarApp? =
        runCatching {
            prefs(context).getString(SWIPE_KEY, null)?.let { json.decodeFromString<BarApp>(it) }
        }.getOrNull() ?: resolveDefault(context, SWIPE_DEFAULT)

    fun saveSwipe(context: Context, app: BarApp?) {
        prefs(context).edit().apply {
            if (app == null) remove(SWIPE_KEY) else putString(SWIPE_KEY, json.encodeToString(app))
        }.apply()
    }

    /** The default's label for settings copy ("default: Maps"), even when overridden. */
    fun defaultFor(context: Context, contextName: String?): BarApp? =
        normalize(contextName)?.let { DEFAULTS[it] }?.let { resolveDefault(context, it) }

    private fun resolveDefault(context: Context, d: Default): BarApp? {
        val matches = AppSearch.launchableApps(context).filter { it.packageName == d.packageName }
        val entry =
            if (d.workProfile) {
                matches.firstOrNull { it.userSerial != null } ?: matches.firstOrNull()
            } else {
                matches.firstOrNull { it.userSerial == null } ?: matches.firstOrNull()
            }
        return entry?.let { BarApp(it.label, it.packageName, it.activityName, it.userSerial) }
    }

    private fun normalize(contextName: String?): String? =
        contextName?.trim()?.takeIf { it.isNotEmpty() }?.let { "@" + it.removePrefix("@") }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
