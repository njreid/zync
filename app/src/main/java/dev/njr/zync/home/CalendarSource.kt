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
