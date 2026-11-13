package app.localdrop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Alerts {
    const val CHANNEL = "transfers_v1"
    fun permitted(c: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun show(c: Context, title: String, message: String) {
        if (!Queue.prefs(c).getBoolean("notifications", true) || !permitted(c)) return
        val manager = c.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Transfer updates", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Receive, copy and queued-share confirmations"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_drop).setContentTitle("LocalDrop").setContentText("Transfer update").build()
        val notification = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(title).setContentText(message).setContentIntent(open)
            .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        // Keep one current update instead of accumulating notifications for every file.
        manager.notify(41, notification)
    }
}
