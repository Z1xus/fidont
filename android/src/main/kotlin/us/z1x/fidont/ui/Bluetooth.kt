package us.z1x.fidont.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.transport.hid.HidStatus

private val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

sealed interface BluetoothState {
    data object Unavailable : BluetoothState

    data object NeedsBluetooth : BluetoothState

    data object Waiting : BluetoothState

    data class Connected(
        val computer: String,
    ) : BluetoothState
}

@Composable
fun rememberBluetooth(): Pair<BluetoothState, () -> Unit> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val key = context.app.hid
    val status by key.status.collectAsState()
    var allowed by remember {
        mutableStateOf(PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
        }
    val show = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    if (allowed) {
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { key.serve() }
        }
    }

    val state =
        when (val current = status) {
            HidStatus.Unavailable -> BluetoothState.Unavailable
            HidStatus.Waiting -> if (allowed) BluetoothState.Waiting else BluetoothState.NeedsBluetooth
            is HidStatus.Connected -> BluetoothState.Connected(current.name)
        }
    return state to {
        // a computer can only pair with a phone that is visible
        if (allowed) show.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)) else allow.launch(PERMISSIONS)
    }
}

@Composable
fun BluetoothRow(
    state: BluetoothState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val connected = state as? BluetoothState.Connected
    Entry(
        index = 0,
        count = 1,
        headline =
            when (state) {
                is BluetoothState.Connected -> stringResource(R.string.bluetooth_connected, state.computer)
                BluetoothState.NeedsBluetooth -> stringResource(R.string.dongle_bluetooth)
                BluetoothState.Unavailable -> stringResource(R.string.bluetooth_unavailable)
                BluetoothState.Waiting -> stringResource(R.string.dongle_searching)
            },
        modifier = modifier,
        supporting =
            when (state) {
                is BluetoothState.Connected -> null
                BluetoothState.NeedsBluetooth -> stringResource(R.string.bluetooth_allow_body)
                BluetoothState.Unavailable -> stringResource(R.string.bluetooth_unavailable_body)
                BluetoothState.Waiting -> stringResource(R.string.bluetooth_waiting_body)
            },
        leading = {
            IconBadge(
                R.drawable.ic_bluetooth,
                if (connected !=
                    null
                ) {
                    scheme.primaryContainer
                } else {
                    scheme.surfaceContainerHighest
                },
            )
        },
        onClick = onClick.takeIf { state == BluetoothState.Waiting || state == BluetoothState.NeedsBluetooth },
    )
}
