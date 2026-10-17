package us.z1x.fidont.transport.hid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.z1x.fidont.BackgroundBluetoothMode
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.ui.MainActivity
import kotlin.time.Duration.Companion.seconds

private const val CHANNEL = "background_bluetooth"
private const val NOTIFICATION = 2
private const val STOP = "us.z1x.fidont.STOP_BACKGROUND_BLUETOOTH"
private const val HANDOFF = "us.z1x.fidont.START_COMPANION_HANDOFF"
private val HANDOFF_TIMEOUT = 30.seconds

/** Keeps the Bluetooth HID registration foreground while Always mode is selected. */
class BluetoothService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var server: Job? = null
    private val bluetooth by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = refresh()
        }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val handoff = intent?.action == HANDOFF && app.preferences.backgroundBluetooth.value == BackgroundBluetoothMode.Companion
        if (intent?.action == STOP || app.preferences.backgroundBluetooth.value != BackgroundBluetoothMode.Always && !handoff) {
            if (intent?.action == STOP) app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Off)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        refresh()
        if (handoff) {
            scope.launch {
                delay(HANDOFF_TIMEOUT)
                if (app.preferences.backgroundBluetooth.value == BackgroundBluetoothMode.Companion) stopSelf()
            }
        }
        return if (handoff) START_NOT_STICKY else START_STICKY
    }

    private fun refresh() {
        if (bluetooth?.isEnabled == true) {
            if (server?.isActive != true) server = scope.launch { app.hid.serve() }
        } else {
            server?.cancel()
            server = null
            app.hid.status.value = HidStatus.Off
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.background_bluetooth_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, BluetoothService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(getString(R.string.background_bluetooth_notification))
            .setContentText(getString(R.string.background_bluetooth_notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.turn_off), stop).build())
            .build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context): Boolean {
            val allowed =
                context.app.preferences.backgroundBluetooth.value == BackgroundBluetoothMode.Always &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (allowed) context.startForegroundService(Intent(context, BluetoothService::class.java))
            return allowed
        }

        fun enable(context: Context) {
            context.app.bluetoothCompanion.disable()
            context.app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Always)
            start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothService::class.java))
        }

        fun handoff(context: Context) {
            context.startForegroundService(Intent(context, BluetoothService::class.java).setAction(HANDOFF))
        }

        fun disable(context: Context) {
            context.app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Off)
            stop(context)
        }
    }
}

class BluetoothBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BluetoothService.start(context)
    }
}
