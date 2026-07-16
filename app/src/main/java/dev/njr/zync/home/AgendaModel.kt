package dev.njr.zync.home

import dev.njr.zync.web.content.NodeView

/** One calendar event on the agenda (from CalendarProvider now; server-pushed work later). */
data class CalEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    /** Which world it belongs to — drives the identity color + chip. */
    val profile: Profile,
    val calendarName: String,
) {
    enum class Profile { HOME, WORK }
}

/** A row of the agenda surface, in render order. */
sealed interface AgendaRow {
    data class Event(val event: CalEvent, val past: Boolean) : AgendaRow
    data object Now : AgendaRow
    /** Free time before [nextTitle]: up to three doable tasks from the current context. */
    data class Gap(val minutes: Int, val nextTitle: String, val tasks: List<NodeView>) : AgendaRow
}

/**
 * Builds the agenda: today's events in time order, a NOW marker, and — when there's
 * a usable gap (≥ [minGapMinutes]) before the next event — a suggestion block with
 * doable tasks from the current context (launcher/home spec; duration-aware later).
 */
fun buildAgenda(
    events: List<CalEvent>,
    now: Long,
    suggestions: List<NodeView>,
    minGapMinutes: Int = 10,
    maxSuggestions: Int = 3,
): List<AgendaRow> {
    val sorted = events.sortedBy { it.beginMillis }
    val busyNow = sorted.any { it.beginMillis <= now && now < it.endMillis }
    val rows = mutableListOf<AgendaRow>()
    var nowInserted = false

    for (event in sorted) {
        val past = event.endMillis <= now
        if (!nowInserted && !past && event.beginMillis > now) {
            rows += AgendaRow.Now
            nowInserted = true
            val gapMinutes = ((event.beginMillis - now) / 60_000).toInt()
            // Free time only counts when nothing is in progress right now.
            if (!busyNow && gapMinutes >= minGapMinutes && suggestions.isNotEmpty()) {
                rows += AgendaRow.Gap(gapMinutes, event.title, suggestions.take(maxSuggestions))
            }
        }
        rows += AgendaRow.Event(event, past)
    }
    if (!nowInserted) rows += AgendaRow.Now // after everything (evening) or an empty day
    return rows
}
