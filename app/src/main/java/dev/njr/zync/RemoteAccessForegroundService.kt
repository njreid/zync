package dev.njr.zync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process (and therefore the LAN pairing/sync server + its NSD advertisement) alive
 * while remote access is enabled, so Android doesn't reclaim the app's network/socket state
 * shortly after it's backgrounded. Started/stopped by whichever UI surface toggles
 * `ZyncApp.remoteAccess` (Task 7); this class only owns the foreground-service plumbing required
 * for that to be legal on API 26+ — it holds no reference to `RemoteAccessManager` itself, since
 * the server keeps running regardless of which component started this service.
 */
class RemoteAccessForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Remote access is active")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "remote_access"
        private const val NOTIFICATION_ID = 1
    }
}
