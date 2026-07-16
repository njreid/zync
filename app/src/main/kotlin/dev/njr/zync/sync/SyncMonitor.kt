package dev.njr.zync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the home-screen sync tile shows (priority order: top wins). */
enum class SyncState { Unpaired, Syncing, NoNetwork, ServerUnreachable, Connected }

/** One line of the sync log. */
@Serializable
data class SyncEvent(val atMillis: Long, val message: String, val ok: Boolean)

/**
 * The phone's view of its relationship with the central server: a small persisted
 * ring of recent sync events (synced/failed/warnings/pairing) plus an in-flight
 * flag, from which the home tile derives one of five states. Events survive
 * process death (prefs JSON) because most syncs happen in a WorkManager process
 * lifetime the user never sees.
 */
object SyncMonitor {
    private const val PREFS = "zync_sync"
    private const val EVENTS = "events"
    private const val LAST_OK = "last_ok"
    private const val MAX_EVENTS = 30
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableStateFlow<List<SyncEvent>>(emptyList())
    val events: StateFlow<List<SyncEvent>> = _events
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** Hydrate the in-memory flow from prefs (call once, at app start). */
    fun load(context: Context) {
        _events.value = runCatching {
            prefs(context).getString(EVENTS, null)?.let { json.decodeFromString<List<SyncEvent>>(it) }
        }.getOrNull() ?: emptyList()
    }

    fun begin() {
        _syncing.value = true
    }

    fun success(context: Context, message: String = "synced") {
        _syncing.value = false
        prefs(context).edit().putBoolean(LAST_OK, true).apply()
        record(context, message, ok = true)
    }

    fun failure(context: Context, reason: String) {
        _syncing.value = false
        prefs(context).edit().putBoolean(LAST_OK, false).apply()
        record(context, "sync failed: $reason", ok = false)
    }

    /**
     * Append an event (newest first). A repeat of the newest message just refreshes
     * its timestamp — the periodic sweep would otherwise fill the whole ring with
     * "synced" lines and push the informative ones out.
     */
    fun record(context: Context, message: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        val current = _events.value
        val head = current.firstOrNull()
        val next =
            if (head != null && head.message == message && head.ok == ok) {
                listOf(head.copy(atMillis = now)) + current.drop(1)
            } else {
                (listOf(SyncEvent(now, message, ok)) + current).take(MAX_EVENTS)
            }
        _events.value = next
        runCatching {
            prefs(context).edit().putString(EVENTS, json.encodeToString(next)).apply()
        }
    }

    /** The tile state right now. */
    fun state(context: Context, paired: Boolean): SyncState =
        state(
            paired = paired,
            syncing = _syncing.value,
            online = hasValidatedInternet(context),
            lastOk = if (prefs(context).contains(LAST_OK)) prefs(context).getBoolean(LAST_OK, true) else null,
        )

    /** Pure priority logic, unit-testable without Android. */
    fun state(paired: Boolean, syncing: Boolean, online: Boolean, lastOk: Boolean?): SyncState =
        when {
            !paired -> SyncState.Unpaired
            syncing -> SyncState.Syncing
            !online -> SyncState.NoNetwork
            lastOk == false -> SyncState.ServerUnreachable
            else -> SyncState.Connected
        }

    fun hasValidatedInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
