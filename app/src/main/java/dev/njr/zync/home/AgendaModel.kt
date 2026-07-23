package dev.njr.zync.home

import dev.njr.zync.web.content.NodeView

/** One calendar event on the agenda (CalendarProvider, notifications, or server-pushed). */
data class CalEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    /** Which world it belongs to — drives the identity color + chip. */
    val profile: Profile,
    val calendarName: String,
    val allDay: Boolean = false,
    /** CalendarProvider event id for deep-linking into the source calendar; 0 = none. */
    val eventId: Long = 0,
    /** True when learned from a system notification rather than a calendar. */
    val fromNotification: Boolean = false,
    /** Where it happens; a URL here becomes a "join" link on the agenda row. */
    val location: String? = null,
    /** Canonical "open in the source calendar" URL (server-pushed events); null = none. */
    val link: String? = null,
) {
    enum class Profile { HOME, WORK }

    /** The first URL inside [location], if any (video-call links ride in there). */
    val joinUrl: String? get() = location?.let { URL_RE.find(it)?.value }

    private companion object {
        val URL_RE = Regex("https?://\\S+")
    }
}

/** A timed row of the agenda, in render order. */
sealed interface AgendaRow {
    data class Event(val event: CalEvent, val past: Boolean, val startingSoon: Boolean) : AgendaRow
    data object Now : AgendaRow
}

/**
 * The agenda surface (device feedback 2026-07-16): all-day events in their own
 * section; when NOT inside an event, a "free for X min" countdown header with up to
 * [maxSuggestions] doable context tasks beneath it; then the timed events with a NOW
 * divider, and anything starting within [soonMinutes] flagged for inverted highlight.
 */
data class AgendaView(
    val allDay: List<CalEvent>,
    /** Minutes until the next event; null = busy right now or feature-less day. */
    val freeMinutes: Int?,
    /** True whenever no event is in progress (suggestions may show). */
    val free: Boolean,
    val suggestions: List<NodeView>,
    val rows: List<AgendaRow>,
    /** The look-ahead: subsequent days' sections (empty days dropped). */
    val upcoming: List<AgendaDay> = emptyList(),
)

/** One future day of the look-ahead. */
data class AgendaDay(val label: String, val allDay: List<CalEvent>, val timed: List<CalEvent>)

/**
 * Pure day-bucketing for the look-ahead: each (label, dayStart, dayEnd) window keeps
 * the events overlapping it (deduped, timed sorted); event-less days are dropped.
 */
fun upcomingDays(events: List<CalEvent>, days: List<Triple<String, Long, Long>>): List<AgendaDay> =
    days.mapNotNull { (label, start, end) ->
        val inDay = events
            .distinctBy { "${it.title}|${it.beginMillis}" }
            .filter { it.beginMillis < end && it.endMillis > start }
        if (inDay.isEmpty()) return@mapNotNull null
        val (allDay, timed) = inDay.partition { it.allDay }
        AgendaDay(label, allDay.sortedBy { it.title }, timed.sortedBy { it.beginMillis })
    }

fun buildAgenda(
    events: List<CalEvent>,
    now: Long,
    suggestions: List<NodeView>,
    soonMinutes: Int = 5,
    maxSuggestions: Int = 3,
    upcoming: List<AgendaDay> = emptyList(),
): AgendaView {
    val (allDay, timed) = events.distinctBy { "${it.title}|${it.beginMillis}" }.partition { it.allDay }
    val sorted = timed.sortedBy { it.beginMillis }
    val busyNow = sorted.any { it.beginMillis <= now && now < it.endMillis }
    val next = sorted.firstOrNull { it.beginMillis > now }

    val rows = mutableListOf<AgendaRow>()
    var nowInserted = false
    for (event in sorted) {
        val past = event.endMillis <= now
        if (!nowInserted && !past && event.beginMillis > now) {
            rows += AgendaRow.Now
            nowInserted = true
        }
        val startingSoon = !past && event.beginMillis > now &&
            (event.beginMillis - now) <= soonMinutes * 60_000L
        rows += AgendaRow.Event(event, past, startingSoon)
    }
    if (!nowInserted) rows += AgendaRow.Now

    return AgendaView(
        allDay = allDay.sortedBy { it.title },
        freeMinutes = if (!busyNow && next != null) ((next.beginMillis - now) / 60_000).toInt() else null,
        free = !busyNow,
        suggestions = if (!busyNow) suggestions.take(maxSuggestions) else emptyList(),
        rows = rows,
        upcoming = upcoming,
    )
}
