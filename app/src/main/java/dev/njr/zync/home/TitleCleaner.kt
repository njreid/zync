package dev.njr.zync.home

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-device (Gemini Nano via the ML Kit Prompt API) cleanup of agenda titles:
 * pithy, Title Case, and any venue embedded in the title moves to the location
 * field (only when the event has none). Results live in a 30-day prefs cache
 * keyed by the RAW title, so each distinct title costs one inference ever —
 * agenda builds stay synchronous (cache hit or raw passthrough) and a tick
 * nudges recomposition when an async clean lands. A DOWNLOADABLE model is
 * downloaded on first use; UNAVAILABLE devices degrade to raw titles.
 */
object TitleCleaner {
    @Serializable
    data class Cleaned(val t: String, val l: String? = null, val at: Long = 0)

    private const val PREFS = "zync_agenda_titles"
    private const val KEY = "cache"
    private const val MAX_ENTRIES = 300
    private const val TTL_MS = 30L * 24 * 60 * 60_000
    private val json = Json { ignoreUnknownKeys = true }

    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inflight = mutableSetOf<String>()
    private val failedThisSession = mutableSetOf<String>()
    @Volatile private var cache: MutableMap<String, Cleaned>? = null
    @Volatile private var nanoUnavailable = false
    private var model: Any? = null

    /** Synchronous polish: cached result applied, or raw event + an async clean queued. */
    fun polish(context: Context, event: CalEvent): CalEvent {
        val raw = event.title.trim()
        if (raw.isEmpty() || raw == "busy") return event
        val hit = load(context)[raw]
        if (hit != null) {
            return event.copy(
                title = hit.t,
                location = event.location ?: hit.l,
            )
        }
        enqueue(context.applicationContext, raw)
        return event
    }

    private fun enqueue(appContext: Context, raw: String) {
        if (nanoUnavailable) return
        synchronized(inflight) {
            if (raw in inflight || raw in failedThisSession) return
            inflight += raw
        }
        scope.launch {
            val outcome = mutex.withLock { generate(raw) }
            synchronized(inflight) { inflight -= raw }
            when (outcome) {
                is Gen.Ok -> {
                    store(appContext, raw, outcome.cleaned)
                    _tick.value++
                }
                is Gen.Retry -> Unit // next agenda rebuild re-enqueues
                is Gen.Failed -> synchronized(inflight) { failedThisSession += raw }
            }
        }
    }

    private sealed interface Gen {
        data class Ok(val cleaned: Cleaned) : Gen
        /** Model busy/downloading — drop from in-flight so a later rebuild retries. */
        data object Retry : Gen
        data object Failed : Gen
    }

    /** One Nano inference through the ML Kit Prompt API (download-on-first-use). */
    private suspend fun generate(raw: String): Gen = runCatching {
        val m = (model as? com.google.mlkit.genai.prompt.GenerativeModel)
            ?: com.google.mlkit.genai.prompt.Generation.getClient().also { model = it }
        when (m.checkStatus()) {
            com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> Unit
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> {
                Log.i("zync", "nano: downloading the model for title cleanup")
                runCatching { m.download().collect { } }
                if (m.checkStatus() != com.google.mlkit.genai.common.FeatureStatus.AVAILABLE) return@runCatching Gen.Retry
            }
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> return@runCatching Gen.Retry
            else -> {
                nanoUnavailable = true
                Log.i("zync", "nano: unavailable on this device; agenda titles stay raw")
                return@runCatching Gen.Failed
            }
        }
        val prompt =
            "Rewrite this calendar event title. Reply with ONLY minified JSON " +
                "{\"title\":string,\"location\":string|null}. The title must be pithy Title Case " +
                "with boilerplate (ticket ids, 'Meeting:', urls, brackets) dropped. If a venue, room, " +
                "address, or place name is embedded in the original, move it to location; else null. " +
                "Original: $raw"
        val text = m.generateContent(prompt).candidates.firstOrNull()?.text
            ?: return@runCatching Gen.Failed
        parseCleaned(text)
            ?.let { Gen.Ok(Cleaned(it.t.ifBlank { raw }.take(80), it.l?.take(120), System.currentTimeMillis())) }
            ?: Gen.Failed
    }.getOrElse {
        Log.i("zync", "nano title cleanup failed for '${raw.take(40)}': ${it.message}")
        Gen.Failed
    }

    /** Tolerates markdown fences and stray prose around the JSON object. */
    fun parseCleaned(text: String): Cleaned? = runCatching {
        val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
        val end = text.lastIndexOf('}').takeIf { it > start } ?: return null
        val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        val title = obj["title"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
        val location = obj["location"]?.jsonPrimitive?.takeIf { it.isString }?.content
        Cleaned(title, location?.takeIf { it.isNotBlank() })
    }.getOrNull()

    private fun load(context: Context): Map<String, Cleaned> {
        cache?.let { return it }
        val now = System.currentTimeMillis()
        val loaded = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { json.decodeFromString<Map<String, Cleaned>>(it) }
        }.getOrNull().orEmpty()
            .filterValues { now - it.at < TTL_MS }
            .toMutableMap()
        cache = loaded
        return loaded
    }

    private fun store(context: Context, raw: String, cleaned: Cleaned) {
        val next = load(context).toMutableMap()
        next[raw] = cleaned
        while (next.size > MAX_ENTRIES) {
            next.remove(next.minByOrNull { it.value.at }?.key ?: break)
        }
        cache = next
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.encodeToString(next.toMap())).apply()
        }
    }
}
