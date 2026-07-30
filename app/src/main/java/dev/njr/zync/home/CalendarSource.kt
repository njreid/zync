package dev.njr.zync.home

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * Today's events from the device CalendarProvider (personal profile). Work-profile
 * events arrive via the backend server later (spec decision) — until an event has a
 * server origin, everything local is [CalEvent.Profile.HOME] unless its calendar
 * name smells like work (best-effort keyword match, refined when server events land).
 */
object CalendarSource {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun todaysEvents(context: Context, dayStartMillis: Long, dayEndMillis: Long): List<CalEvent> {
        if (!hasPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, dayStartMillis)
            ContentUris.appendId(it, dayEndMillis)
            it.build()
        }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val excluded = CalendarChoices.excluded(context)
        val events = mutableListOf<CalEvent>()
        runCatching {
            context.contentResolver.query(
                uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )
                ?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getLong(7).toString() in excluded) continue
                        val name = c.getString(3) ?: ""
                        val allDay = c.getInt(4) == 1
                        // All-day instances are UTC midnights; our day windows are LOCAL
                        // midnights — unconverted, a Saturday all-day event overlaps local
                        // Sunday in any UTC+ zone (device feedback 2026-07-19).
                        val begin = c.getLong(1).let { if (allDay) utcMidnightToLocalMidnight(it) else it }
                        val end = c.getLong(2).let { if (allDay) utcMidnightToLocalMidnight(it) else it }
                        events += CalEvent(
                            title = c.getString(0) ?: "(untitled)",
                            beginMillis = begin,
                            endMillis = end,
                            profile = if (WORK_HINTS.any { name.contains(it, ignoreCase = true) }) {
                                CalEvent.Profile.WORK
                            } else {
                                CalEvent.Profile.HOME
                            },
                            calendarName = name,
                            allDay = allDay,
                            eventId = c.getLong(5),
                            location = c.getString(6)?.takeIf { it.isNotBlank() },
                        )
                    }
                }
        }
        return events
    }

    /** One pickable device calendar (settings → Agenda tab). */
    data class CalInfo(val id: String, val name: String, val account: String)

    /** All visible calendars on the device, for the include/exclude picker. */
    fun availableCalendars(context: Context): List<CalInfo> {
        if (!hasPermission(context)) return emptyList()
        val out = mutableListOf<CalInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                ),
                "${CalendarContract.Calendars.VISIBLE} = 1", null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalInfo(c.getLong(0).toString(), c.getString(1) ?: "(calendar)", c.getString(2) ?: "")
                }
            }
        }
        return out
    }

    /** Local midnight at the start of the day CONTAINING [millis]. */
    fun startOfLocalDay(millis: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        java.util.Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Snap an ALL-DAY event onto the DEVICE's local calendar days, regardless of the timezone
     * its source encoded it in. CalendarProvider events already arrive at local midnight; server
     * feeds may encode all-day at UTC — or the calendar's — midnight, so their [begin, end) window
     * pokes past a local-midnight boundary and matches TWO adjacent day buckets (the same event
     * shows today AND tomorrow). We derive each endpoint's intended date from a noon anchor (robust
     * to any zone offset within ±12h) and rebuild the span on local midnights; timed events pass
     * through untouched. This also makes a server copy and a local copy of the same all-day event
     * snap to identical begin millis, so the (title|begin) dedup finally merges them.
     */
    fun normalizeAllDay(event: CalEvent, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): CalEvent {
        if (!event.allDay) return event
        val halfDay = 12L * 60 * 60_000
        val begin = startOfLocalDay(event.beginMillis + halfDay, zone)
        // end is the EXCLUSIVE next-midnight; step 12h back into the last covered day, then to its
        // following midnight — a single-day event collapses to exactly one [midnight, +24h) window.
        val lastDayStart = startOfLocalDay(event.endMillis - halfDay, zone)
        val end = maxOf(lastDayStart, begin) + 24L * 60 * 60_000
        return event.copy(beginMillis = begin, endMillis = end)
    }

    /** The same UTC calendar date, as a LOCAL-midnight timestamp. */
    fun utcMidnightToLocalMidnight(
        utcMillis: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Long {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return java.util.Calendar.getInstance(zone).apply {
            clear()
            set(
                utc.get(java.util.Calendar.YEAR),
                utc.get(java.util.Calendar.MONTH),
                utc.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }.timeInMillis
    }

    /** Deep link into the source calendar's event detail. */
    fun viewIntent(event: CalEvent): android.content.Intent? {
        if (event.eventId == 0L) return null
        return android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId),
        )
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val WORK_HINTS = listOf("work", "office", "corp")
}

/**
 * Individual agenda events hidden by (source, title) — e.g. an all-day work-location "Home".
 * Keyed on both so hiding one calendar's "Home" doesn't hide another's. Settings → Agenda tab.
 */
object EventFilters {
    private const val PREFS = "zync_launcher"
    private const val KEY = "agenda_hidden_events"

    /** A stable key for a (source, title) pair (NUL-separated so neither field can collide). */
    fun key(source: String, title: String) = "$source\u0000$title"

    fun hidden(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isHidden(context: Context, source: String, title: String): Boolean = key(source, title) in hidden(context)

    fun setHidden(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, keys).apply()
    }
}

/** Which device calendars the agenda skips (settings → Agenda tab); empty = all. */
object CalendarChoices {
    private const val PREFS = "zync_launcher"
    private const val KEY = "agenda_excluded_cals"

    fun excluded(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    fun setExcluded(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, ids).apply()
    }
}
