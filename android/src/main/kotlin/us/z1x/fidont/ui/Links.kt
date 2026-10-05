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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

// a person compares the codes of a computer within this time
private val PAIR_TIMEOUT = 60.seconds

class Links(
    val dongle: DongleState,
    val onDongle: (DongleAction) -> Unit,
    val helper: HelperState,
    val onHelper: (HelperAction) -> Unit,
)

@Composable
fun rememberLinks(): Links {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dongle = context.app.dongle
    val usb = remember { context.getSystemService(UsbManager::class.java) }
    val links by dongle.links.collectAsState()
    val statuses by dongle.status.collectAsState()
    val paired = links.firstOrNull { it.computer == null }
    val computers = links.filter { it.computer != null }
    var board by remember { mutableStateOf<UsbDevice?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var pairing by remember { mutableStateOf<Job?>(null) }
    var computer by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    val answer = remember { Channel<Boolean>(Channel.CONFLATED) }
    var failure by remember { mutableStateOf<DongleState.Failure?>(null) }
    var missed by remember { mutableStateOf(false) }
    var allowed by remember {
        mutableStateOf(BLUETOOTH.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
        }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pair =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
            if (allowed) {
                failure = null
                missed = false
                pairing =
                    scope.launch {
                        try {
                            val done =
                                withTimeoutOrNull(PAIR_TIMEOUT) {
                                    dongle.pair(computer) {
                                        code = it
                                        answer.receive()
                                    }
                                }
                            if (done != true && computer) missed = true
                            if (done != true && !computer) failure = DongleState.Failure.Pair
                        } finally {
                            pairing = null
                            code = null
                        }
                    }
            }
        }

    LaunchedEffect(Unit) {
        while (true) {
            if (progress == null) board = plugged(usb)
            delay(1.seconds)
        }
    }
    if (allowed && links.isNotEmpty()) {
        LaunchedEffect(links) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    dongle.watch(false)
                    dongle.serve()
                } finally {
                    dongle.watch(true)
                }
            }
        }
    }
    // the notice is how a computer reaches the phone while the app is closed
    LaunchedEffect(computers.isEmpty()) {
        if (computers.isNotEmpty()) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val state =
        when {
            progress != null -> {
                DongleState.SettingUp(progress ?: 0f)
            }

            pairing != null && !computer -> {
                DongleState.Pairing
            }

            paired == null || board != null -> {
                DongleState.Unpaired(board != null, failure)
            }

            !allowed -> {
                DongleState.NeedsBluetooth
            }

            else -> {
                when (val current = statuses[paired.id]) {
                    null, Status.Searching -> DongleState.Searching
                    Status.Connected -> DongleState.Connected
                    is Status.Updating -> DongleState.Updating(current.progress)
                }
            }
        }
    val helper =
        HelperState(
            computers = computers.map { Helper(it.id, it.computer.orEmpty(), statuses[it.id] == Status.Connected) },
            allowed = allowed,
            pairing =
                when {
                    !computer || pairing == null -> Pairing.Idle(missed)
                    else -> code?.let(Pairing::Confirm) ?: Pairing.Searching
                },
        )
    return Links(
        dongle = state,
        onDongle = { action ->
            when (action) {
                DongleAction.SetUp -> {
                    // an empty board restarts in a loop, and each start is a new device to Android
                    plugged(usb)?.let { device ->
                        scope.launch {
                            failure = null
                            progress = 0f
                            if (!usbPermission(context, usb, device) || !dongle.setUp(device) { progress = it }) {
                                failure = DongleState.Failure.SetUp
                            }
                            board = null
                            progress = null
                        }
                    }
                }

                DongleAction.Pair -> {
                    computer = false
                    pair.launch(BLUETOOTH)
                }

                DongleAction.Cancel -> {
                    pairing?.cancel()
                }

                DongleAction.Allow -> {
                    allow.launch(BLUETOOTH)
                }

                DongleAction.Forget -> {
                    paired?.let { dongle.forget(it.id) }
                }
            }
        },
        helper = helper,
        onHelper = { action ->
            when (action) {
                HelperAction.Pair -> {
                    computer = true
                    pair.launch(BLUETOOTH)
                }

                HelperAction.Cancel -> {
                    pairing?.cancel()
                }

                HelperAction.Confirm -> {
                    answer.trySend(true)
                }

                HelperAction.Allow -> {
                    allow.launch(BLUETOOTH)
                }

                is HelperAction.Forget -> {
                    dongle.forget(action.id)
                }
            }
        },
    )
}

private fun plugged(usb: UsbManager) = usb.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF && it.productId == SERIAL_JTAG }

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
