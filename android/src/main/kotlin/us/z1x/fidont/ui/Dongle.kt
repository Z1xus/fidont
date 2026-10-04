package us.z1x.fidont.ui

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import us.z1x.fidont.app
import us.z1x.fidont.transport.dongle.Status
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

private const val ESPRESSIF = 0x303a
private const val SERIAL_JTAG = 0x1001
private val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
private val PAIR_TIMEOUT = 30.seconds

// Android throttles an app that starts more than 5 scans in 30 seconds
private val RETRY = 6.seconds

@Composable
fun rememberDongle(): Pair<DongleState, (DongleAction) -> Unit> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dongle = context.app.dongle
    val usb = remember { context.getSystemService(UsbManager::class.java) }
    val secret by dongle.secret.collectAsState()
    val status by dongle.status.collectAsState()
    var board by remember { mutableStateOf<UsbDevice?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<DongleState.Failure?>(null) }
    var allowed by remember {
        mutableStateOf(BLUETOOTH.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
        }
    val pair =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
            if (allowed) {
                scope.launch {
                    failure = null
                    pairing = true
                    if (withTimeoutOrNull(PAIR_TIMEOUT) { dongle.pair() } != true) failure = DongleState.Failure.Pair
                    pairing = false
                }
            }
        }

    if (secret == null) {
        LaunchedEffect(Unit) {
            while (true) {
                board = usb.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF && it.productId == SERIAL_JTAG }
                delay(1.seconds)
            }
        }
    } else if (allowed) {
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    dongle.serve()
                    delay(RETRY)
                }
            }
        }
    }

    val state =
        when {
            progress != null -> {
                DongleState.SettingUp(progress ?: 0f)
            }

            pairing -> {
                DongleState.Pairing
            }

            secret == null -> {
                DongleState.Unpaired(board != null, failure)
            }

            !allowed -> {
                DongleState.NeedsBluetooth
            }

            else -> {
                when (val current = status) {
                    Status.Searching -> DongleState.Searching
                    Status.Connected -> DongleState.Connected
                    is Status.Updating -> DongleState.Updating(current.progress)
                }
            }
        }
    return state to { action ->
        when (action) {
            DongleAction.SetUp -> {
                board?.let { device ->
                    scope.launch {
                        failure = null
                        progress = 0f
                        if (!usbPermission(context, usb, device) || !dongle.setUp(device) { progress = it }) {
                            failure = DongleState.Failure.SetUp
                        }
                        progress = null
                    }
                }
            }

            DongleAction.Pair -> {
                pair.launch(BLUETOOTH)
            }

            DongleAction.Allow -> {
                allow.launch(BLUETOOTH)
            }

            DongleAction.Forget -> {
                dongle.forget()
            }
        }
    }
}

private suspend fun usbPermission(
    context: Context,
    usb: UsbManager,
    device: UsbDevice,
): Boolean {
    if (usb.hasPermission(device)) return true
    return suspendCancellableCoroutine { continuation ->
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    context.unregisterReceiver(this)
                    continuation.resume(usb.hasPermission(device))
                }
            }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val intent = Intent(action).setPackage(context.packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE))
        continuation.invokeOnCancellation { context.unregisterReceiver(receiver) }
    }
}
