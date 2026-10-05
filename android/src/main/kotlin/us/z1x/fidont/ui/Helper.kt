package us.z1x.fidont.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import us.z1x.fidont.R

class Helper(
    val id: String,
    val name: String,
    val connected: Boolean,
)

sealed interface Pairing {
    data class Idle(
        val missed: Boolean = false,
    ) : Pairing

    data object Searching : Pairing

    data class Confirm(
        val code: String,
    ) : Pairing
}

class HelperState(
    val computers: List<Helper>,
    val allowed: Boolean = true,
    val pairing: Pairing = Pairing.Idle(),
)

sealed interface HelperAction {
    data object Pair : HelperAction

    data object Cancel : HelperAction

    data object Confirm : HelperAction

    data object Allow : HelperAction

    class Forget(
        val id: String,
    ) : HelperAction
}

@Composable
fun HelperRow(
    computer: Helper,
    allowed: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Entry(
        index = index,
        count = count,
        headline = computer.name,
        modifier = modifier,
        supporting = stringResource(status(computer, allowed)),
        leading = {
            IconBadge(R.drawable.ic_computer, if (computer.connected) scheme.primaryContainer else scheme.surfaceContainerHighest)
        },
        onClick = onClick,
    )
}

// shows one paired computer, or the steps to pair a new one
@Composable
fun HelperSheet(
    state: HelperState,
    computer: Helper?,
    onAction: (HelperAction) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pairing = state.pairing
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .animateContentSize()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            R.drawable.ic_computer,
            if (computer?.connected == true) scheme.primaryContainer else scheme.surfaceContainerHighest,
            64.dp,
        )
        Text(
            when {
                computer != null -> computer.name
                pairing is Pairing.Confirm -> pairing.code
                pairing is Pairing.Searching -> stringResource(R.string.dongle_pairing)
                pairing == Pairing.Idle(missed = true) -> stringResource(R.string.helper_missed)
                else -> stringResource(R.string.helper_add)
            },
            Modifier.padding(top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(
                when {
                    computer != null && !state.allowed -> R.string.bluetooth_allow_body
                    computer != null && computer.connected -> R.string.dongle_connected_body
                    computer != null -> R.string.helper_waiting_body
                    pairing is Pairing.Confirm -> R.string.helper_confirm_body
                    pairing is Pairing.Searching -> R.string.helper_pairing_body
                    else -> R.string.helper_add_body
                },
            ),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        when {
            computer != null -> {
                if (!state.allowed) {
                    Button(onClick = { onAction(HelperAction.Allow) }, Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        Text(stringResource(R.string.allow))
                    }
                }
                Button(
                    onClick = { onAction(HelperAction.Forget(computer.id)) },
                    modifier = Modifier.fillMaxWidth().padding(top = if (state.allowed) 32.dp else 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                ) { Text(stringResource(R.string.forget)) }
            }

            pairing is Pairing.Idle -> {
                Button(onClick = { onAction(HelperAction.Pair) }, Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    Text(stringResource(R.string.helper_pair))
                }
            }

            else -> {
                if (pairing is Pairing.Confirm) {
                    Button(onClick = { onAction(HelperAction.Confirm) }, Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        Text(stringResource(R.string.helper_confirm))
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp))
                }
                TextButton(onClick = { onAction(HelperAction.Cancel) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

private fun status(
    computer: Helper,
    allowed: Boolean,
): Int =
    when {
        !allowed -> R.string.dongle_bluetooth
        computer.connected -> R.string.dongle_connected
        else -> R.string.dongle_searching
    }
