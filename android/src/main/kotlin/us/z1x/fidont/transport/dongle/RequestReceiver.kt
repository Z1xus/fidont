package us.z1x.fidont.transport.dongle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import us.z1x.fidont.R
import us.z1x.fidont.ui.MainActivity

private const val CHANNEL = "requests"
const val REQUEST_NOTIFICATION = 1

// Android calls this when a request waits while the app is closed
class RequestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val associated = context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations.orEmpty()
        if (associated.isNotEmpty()) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        } else if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            context.getSystemService(NotificationManager::class.java).notify(REQUEST_NOTIFICATION, requestNotification(context))
        }
    }
}

fun requestNotification(context: Context): Notification {
    val name = context.getString(R.string.request_channel)
    val channel = NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return Notification
        .Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_key)
        .setContentTitle(context.getString(R.string.request_title))
        .setContentText(context.getString(R.string.request_body))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build()
}
