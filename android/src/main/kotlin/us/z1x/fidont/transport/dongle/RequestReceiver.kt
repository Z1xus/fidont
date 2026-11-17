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
import android.os.Build
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
        val companions = context.getSystemService(CompanionDeviceManager::class.java)
        val associated = if (Build.VERSION.SDK_INT >= 33) companions?.myAssociations?.isNotEmpty() else companions?.associations?.isNotEmpty()
        if (associated == true) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        } else if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(NotificationManager::class.java).notify(REQUEST_NOTIFICATION, linkNotification(context, open))
        }
    }
}

fun requestNotification(
    context: Context,
    approval: PendingIntent,
): Notification =
    notification(context)
        .setContentTitle(context.getString(R.string.request_title))
        .setContentText(context.getString(R.string.request_body))
        .setContentIntent(approval)
        .build()

fun linkNotification(
    context: Context,
    connect: PendingIntent? = null,
): Notification =
    notification(context)
        .setContentTitle(context.getString(R.string.bluetooth))
        .setContentText(context.getString(if (connect == null) R.string.link_notification_body else R.string.link_notification_open))
        .setContentIntent(connect)
        .setAutoCancel(connect != null)
        .build()

private fun notification(context: Context): Notification.Builder {
    val name = context.getString(R.string.request_channel)
    val channel = NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    return Notification
        .Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_key)
        .setOnlyAlertOnce(true)
}
