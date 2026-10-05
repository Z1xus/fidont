package us.z1x.fidont.transport.dongle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import us.z1x.fidont.R
import us.z1x.fidont.ui.MainActivity

private const val CHANNEL = "requests"
const val REQUEST_NOTIFICATION = 1

// Android calls this when a paired computer shows up while the app is closed
class RequestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(R.string.request_channel)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle(context.getString(R.string.request_title))
                .setContentText(context.getString(R.string.request_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        manager.notify(REQUEST_NOTIFICATION, notification)
    }
}
