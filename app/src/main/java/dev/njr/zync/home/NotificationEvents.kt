package dev.njr.zync.home

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Agenda items learned from system notifications (device feedback 2026-07-16):
 * an event/reminder notification for something no calendar told us about goes
 * straight onto the agenda. In-memory for the day; merged (and deduped against
 * calendar events) at agenda-build time.
 */
object NotificationEvents {
    private val flow = MutableStateFlow<List<CalEvent>>(emptyList())
    val events: StateFlow<List<CalEvent>> = flow

    /** The source notification's tap action, keyed like the dedupe key — a
     *  notification event usually has NO calendar entry to deep-link into. */
    private val intents = HashMap<String, android.app.PendingIntent>()

    fun add(event: CalEvent, contentIntent: android.app.PendingIntent? = null) {
        if (contentIntent != null) intents[key(event)] = contentIntent
        flow.value = (flow.value + event)
            .distinctBy { "${it.title}|${it.beginMillis}" }
            .filter { it.endMillis > System.currentTimeMillis() - 60 * 60_000 }
        intents.keys.retainAll(flow.value.map(::key).toSet())
    }

    /** Fire the original notification's launch action; false when gone/cancelled. */
    fun launch(event: CalEvent): Boolean =
        intents[key(event)]?.let { runCatching { it.send() }.isSuccess } ?: false

    private fun key(event: CalEvent) = "${event.title}|${event.beginMillis}"

    fun listenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

/**
 * Listens for event/reminder notifications system-wide (user enables the listener in
 * system settings; the agenda shows an enable row until then).
 */
class ZyncNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val isEventish = n.category == Notification.CATEGORY_EVENT ||
            n.category == Notification.CATEGORY_REMINDER ||
            n.category == Notification.CATEGORY_ALARM
        if (!isEventish) return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        if (title.isEmpty()) return
        val begin = if (n.`when` > System.currentTimeMillis() - 5 * 60_000) n.`when` else System.currentTimeMillis()
        NotificationEvents.add(
            CalEvent(
                title = title,
                beginMillis = begin,
                endMillis = begin + 30 * 60_000,
                profile = CalEvent.Profile.HOME,
                calendarName = sbn.packageName,
                fromNotification = true,
            ),
            contentIntent = n.contentIntent,
        )
    }

    companion object {
        fun componentName(context: Context) = ComponentName(context, ZyncNotificationListener::class.java)
    }
}
