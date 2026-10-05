package us.z1x.fidont.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.transport.hid.HidStatus
import kotlin.time.Duration.Companion.seconds

private val PERMISSIONS =
    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)

class Computer(
    val name: String,
    val address: String,
)

sealed interface BluetoothState {
    data object Unsupported : BluetoothState

    data object Off : BluetoothState

    data object NeedsBluetooth : BluetoothState

    data object Starting : BluetoothState

    class Ready(
        val computers: List<Computer>,
        val nearby: List<Computer> = emptyList(),
        val searching: Boolean = false,
        val searched: Boolean = false,
        val failed: Boolean = false,
    ) : BluetoothState

    data class Connecting(
        val computer: String,
    ) : BluetoothState

    data class Connected(
        val computer: String,
    ) : BluetoothState
}

sealed interface BluetoothAction {
    data object Allow : BluetoothAction

    data object Pair : BluetoothAction

    data object Cancel : BluetoothAction

    data object Disconnect : BluetoothAction

    class Connect(
        val address: String,
    ) : BluetoothAction
}

@Composable
fun rememberBluetooth(): Pair<BluetoothState, (BluetoothAction) -> Unit> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val key = context.app.hid
    val status by key.status.collectAsState()
    val nearby by key.nearby.collectAsState()
    val searching by key.searching.collectAsState()
    var allowed by remember {
        mutableStateOf(PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
        }
    var searched by remember { mutableStateOf(false) }

    if (allowed) {
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { key.serve() }
        }
    }
    val state =
        when (val current = status.takeIf { allowed }) {
            null -> {
                BluetoothState.NeedsBluetooth
            }

            HidStatus.Unsupported -> {
                BluetoothState.Unsupported
            }

            HidStatus.Off -> {
                BluetoothState.Off
            }

            HidStatus.Starting -> {
                BluetoothState.Starting
            }

            is HidStatus.Connecting -> {
                BluetoothState.Connecting(current.name)
            }

            is HidStatus.Connected -> {
                BluetoothState.Connected(current.name)
            }

            is HidStatus.Ready -> {
                BluetoothState.Ready(
                    computers = key.computers.map { Computer(it.name ?: it.address, it.address) },
                    nearby = nearby.map { Computer(it.name ?: it.address, it.address) },
                    searching = searching,
                    searched = searched,
                    failed = current.failed,
                )
            }
        }
    return state to { action ->
        when (action) {
            BluetoothAction.Allow -> {
                allow.launch(PERMISSIONS)
            }

            BluetoothAction.Pair -> {
                searched = true
                key.search()
            }

            BluetoothAction.Cancel -> {
                searched = false
                key.cancel()
            }

            BluetoothAction.Disconnect -> {
                key.disconnect()
            }

            is BluetoothAction.Connect -> {
                key.connect(action.address)
            }
        }
    }
}

@Composable
fun BluetoothRow(
    state: BluetoothState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Entry(
        index = 0,
        count = 1,
        headline = title(state),
        modifier = modifier,
        supporting =
            when (state) {
                BluetoothState.Unsupported -> stringResource(R.string.bluetooth_unsupported_body)
                BluetoothState.Off -> stringResource(R.string.bluetooth_off_body)
                BluetoothState.NeedsBluetooth -> stringResource(R.string.bluetooth_allow_body)
                else -> null
            },
        leading = {
            IconBadge(
                R.drawable.ic_bluetooth,
                if (state is BluetoothState.Connected) scheme.primaryContainer else scheme.surfaceContainerHighest,
            )
        },
        onClick = onClick.takeUnless { state == BluetoothState.Unsupported || state == BluetoothState.Off },
    )
}

@Composable
fun BluetoothSheet(
    state: BluetoothState,
    onAction: (BluetoothAction) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .animateContentSize()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            R.drawable.ic_bluetooth,
            if (state is BluetoothState.Connected) scheme.primaryContainer else scheme.surfaceContainerHighest,
            64.dp,
        )
        Text(
            title(state),
            Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            body(state),
            Modifier.padding(horizontal = 24.dp),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        when (state) {
            is BluetoothState.Ready -> {
                if (state.computers.isNotEmpty()) SectionHeader(R.string.bluetooth_paired, Modifier.fillMaxWidth())
                state.computers.forEachIndexed { index, computer ->
                    Entry(index, state.computers.size, computer.name, onClick = { onAction(BluetoothAction.Connect(computer.address)) })
                }
                if (state.nearby.isNotEmpty()) SectionHeader(R.string.bluetooth_nearby, Modifier.fillMaxWidth())
                state.nearby.forEachIndexed { index, computer ->
                    Entry(index, state.nearby.size, computer.name, onClick = { onAction(BluetoothAction.Connect(computer.address)) })
                }
                if (state.searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 8.dp))
                    TextButton(
                        onClick = { onAction(BluetoothAction.Cancel) },
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    ) { Text(stringResource(R.string.cancel)) }
                } else {
                    TextButton(
                        onClick = { onAction(BluetoothAction.Pair) },
                        Modifier.fillMaxWidth().padding(start = 24.dp, top = 24.dp, end = 24.dp),
                    ) { Text(stringResource(R.string.bluetooth_pair)) }
                }
            }

            is BluetoothState.Connected -> {
                Button(
                    onClick = { onAction(BluetoothAction.Disconnect) },
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 32.dp, end = 24.dp),
                ) { Text(stringResource(R.string.bluetooth_disconnect)) }
            }

            BluetoothState.NeedsBluetooth -> {
                Button(
                    onClick = { onAction(BluetoothAction.Allow) },
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 32.dp, end = 24.dp),
                ) { Text(stringResource(R.string.allow)) }
            }

            BluetoothState.Starting, is BluetoothState.Connecting -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 8.dp))
            }

            else -> {}
        }
    }
}

@Composable
private fun title(state: BluetoothState): String =
    when (state) {
        BluetoothState.Unsupported -> stringResource(R.string.bluetooth_unsupported)
        BluetoothState.Off -> stringResource(R.string.bluetooth_off)
        BluetoothState.NeedsBluetooth -> stringResource(R.string.dongle_bluetooth)
        BluetoothState.Starting -> stringResource(R.string.bluetooth_starting)
        is BluetoothState.Ready -> stringResource(if (state.searching) R.string.bluetooth_searching else R.string.dongle_searching)
        is BluetoothState.Connecting -> stringResource(R.string.bluetooth_connecting, state.computer)
        is BluetoothState.Connected -> stringResource(R.string.bluetooth_connected, state.computer)
    }

@Composable
private fun body(state: BluetoothState): String =
    when (state) {
        BluetoothState.Unsupported -> {
            stringResource(R.string.bluetooth_unsupported_body)
        }

        BluetoothState.Off -> {
            stringResource(R.string.bluetooth_off_body)
        }

        BluetoothState.NeedsBluetooth -> {
            stringResource(R.string.bluetooth_allow_body)
        }

        BluetoothState.Starting -> {
            stringResource(R.string.bluetooth_starting_body)
        }

        is BluetoothState.Connecting -> {
            stringResource(R.string.bluetooth_connecting_body)
        }

        is BluetoothState.Connected -> {
            stringResource(R.string.bluetooth_connected_body)
        }

        is BluetoothState.Ready -> {
            when {
                state.searching || state.nearby.isNotEmpty() -> stringResource(R.string.bluetooth_pairing_body)
                state.searched -> stringResource(R.string.bluetooth_none_body)
                state.failed -> stringResource(R.string.bluetooth_failed_body)
                else -> stringResource(R.string.bluetooth_ready_body)
            }
        }
    }
