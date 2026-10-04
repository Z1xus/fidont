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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.transport.dongle.Dongle
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
fun DongleCard() {
    val dongle = LocalContext.current.app.dongle
    val secret by dongle.secret.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.dongle), style = MaterialTheme.typography.titleMedium)
            if (secret == null) Setup(dongle) else Linked(dongle)
        }
    }
}

@Composable
private fun ColumnScope.Setup(dongle: Dongle) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usb = remember { context.getSystemService(UsbManager::class.java) }
    var board by remember { mutableStateOf<UsbDevice?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val pair =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                scope.launch {
                    failure = null
                    pairing = true
                    if (withTimeoutOrNull(PAIR_TIMEOUT) { dongle.pair() } != true) failure = R.string.dongle_pair_failed
                    pairing = false
                }
            }
        }

    LaunchedEffect(Unit) {
        while (true) {
            board = usb.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF && it.productId == SERIAL_JTAG }
            delay(1.seconds)
        }
    }

    val text =
        when {
            progress != null -> R.string.dongle_setting_up
            pairing -> R.string.dongle_pairing
            failure != null -> failure
            board != null -> R.string.dongle_found
            else -> R.string.dongle_none
        }
    Text(stringResource(text ?: R.string.dongle_none), style = MaterialTheme.typography.bodyMedium)
    progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)) }
    Row(Modifier.align(Alignment.End)) {
        TextButton(enabled = progress == null && !pairing, onClick = { pair.launch(BLUETOOTH) }) {
            Text(stringResource(R.string.dongle_pair))
        }
        Button(
            enabled = board != null && progress == null && !pairing,
            onClick = {
                val device = board ?: return@Button
                scope.launch {
                    failure = null
                    progress = 0f
                    if (!allowed(context, usb, device) || !dongle.setUp(device) { progress = it }) failure = R.string.dongle_failed
                    progress = null
                }
            },
        ) { Text(stringResource(R.string.dongle_set_up)) }
    }
}

@Composable
private fun ColumnScope.Linked(dongle: Dongle) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val status by dongle.status.collectAsState()
    var forgetting by remember { mutableStateOf(false) }
    var allowed by remember {
        mutableStateOf(BLUETOOTH.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
        }

    if (allowed) {
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    dongle.serve()
                    delay(RETRY)
                }
            }
        }
    }

    val text =
        when (status) {
            Status.Searching -> R.string.dongle_searching
            Status.Connected -> R.string.dongle_connected
            is Status.Updating -> R.string.dongle_updating
        }
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    (status as? Status.Updating)?.let {
        LinearProgressIndicator(progress = { it.progress }, Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
    }
    Row(Modifier.align(Alignment.End)) {
        TextButton(onClick = { forgetting = true }) { Text(stringResource(R.string.dongle_forget)) }
        if (!allowed) Button(onClick = { allow.launch(BLUETOOTH) }) { Text(stringResource(R.string.dongle_bluetooth)) }
    }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.dongle_forget_title)) },
            text = { Text(stringResource(R.string.dongle_forget_body)) },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = dongle::forget) { Text(stringResource(R.string.dongle_forget)) } },
        )
    }
}

private suspend fun allowed(
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
