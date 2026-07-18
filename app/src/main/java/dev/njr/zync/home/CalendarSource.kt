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
        )
        val events = mutableListOf<CalEvent>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(3) ?: ""
                        events += CalEvent(
                            title = c.getString(0) ?: "(untitled)",
                            beginMillis = c.getLong(1),
                            endMillis = c.getLong(2),
                            profile = if (WORK_HINTS.any { name.contains(it, ignoreCase = true) }) {
                                CalEvent.Profile.WORK
                            } else {
                                CalEvent.Profile.HOME
                            },
                            calendarName = name,
                            allDay = c.getInt(4) == 1,
                            eventId = c.getLong(5),
                            location = c.getString(6)?.takeIf { it.isNotBlank() },
                        )
                    }
                }
        }
        return events
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
