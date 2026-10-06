package us.z1x.fidont.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import us.z1x.fidont.R

sealed interface DongleState {
    data class Unpaired(
        val board: Boolean,
        val failure: Failure? = null,
    ) : DongleState

    data class SettingUp(
        val progress: Float,
    ) : DongleState

    data object Pairing : DongleState

    data object NeedsBluetooth : DongleState

    data object Searching : DongleState

    data object Connected : DongleState

    data class Updating(
        val progress: Float,
    ) : DongleState

    enum class Failure { Download, SetUp, Pair }
}

enum class DongleAction { SetUp, Pair, Cancel, Allow, Forget }

@Composable
fun DongleRow(
    state: DongleState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupItem(0, 1, modifier, onClick) {
        ListItem(
            headlineContent = { AnimatedContent(title(state), label = "status") { Text(stringResource(it)) } },
            leadingContent = { IconBadge(R.drawable.ic_usb, badge(state)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun DongleSheet(
    state: DongleState,
    onAction: (DongleAction) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .animateContentSize()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(R.drawable.ic_usb, badge(state), 64.dp)
        Text(stringResource(title(state)), Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(body(state)),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        when (state) {
            is DongleState.SettingUp -> {
                Progress(state.progress)
            }

            is DongleState.Updating -> {
                Progress(state.progress)
            }

            DongleState.Pairing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp))
                TextButton(onClick = { onAction(DongleAction.Cancel) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }

            is DongleState.Unpaired -> {
                if (state.board) {
                    Button(onClick = { onAction(DongleAction.SetUp) }, Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        Text(stringResource(R.string.dongle_set_up))
                    }
                }
                TextButton(
                    onClick = { onAction(DongleAction.Pair) },
                    Modifier.fillMaxWidth().padding(top = if (state.board) 4.dp else 24.dp),
                ) {
                    Text(stringResource(R.string.dongle_pair))
                }
            }

            else -> {
                if (state == DongleState.NeedsBluetooth) {
                    Button(onClick = { onAction(DongleAction.Allow) }, Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        Text(stringResource(R.string.allow))
                    }
                }
                Button(
                    onClick = { onAction(DongleAction.Forget) },
                    modifier = Modifier.fillMaxWidth().padding(top = if (state == DongleState.NeedsBluetooth) 4.dp else 32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                ) { Text(stringResource(R.string.dongle_forget)) }
            }
        }
    }
}

@Composable
private fun Progress(progress: Float) {
    val animated by animateFloatAsState(progress, label = "progress")
    LinearProgressIndicator(progress = { animated }, Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp))
}

@Composable
private fun badge(state: DongleState): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        state == DongleState.Connected -> scheme.primaryContainer
        state is DongleState.Unpaired && state.failure != null -> scheme.errorContainer
        else -> scheme.surfaceContainerHighest
    }
}

private fun title(state: DongleState): Int =
    when (state) {
        is DongleState.Unpaired -> {
            when {
                state.failure == DongleState.Failure.Download -> R.string.dongle_download_failed
                state.failure == DongleState.Failure.SetUp -> R.string.dongle_failed
                state.failure == DongleState.Failure.Pair -> R.string.dongle_pair_failed
                state.board -> R.string.dongle_found
                else -> R.string.dongle_none
            }
        }

        is DongleState.SettingUp -> {
            R.string.dongle_setting_up
        }

        DongleState.Pairing -> {
            R.string.dongle_pairing
        }

        DongleState.NeedsBluetooth -> {
            R.string.dongle_bluetooth
        }

        DongleState.Searching -> {
            R.string.dongle_searching
        }

        DongleState.Connected -> {
            R.string.dongle_connected
        }

        is DongleState.Updating -> {
            R.string.dongle_updating
        }
    }

private fun body(state: DongleState): Int =
    when (state) {
        is DongleState.Unpaired -> {
            when {
                state.failure == DongleState.Failure.Download -> R.string.dongle_download_failed_body
                state.failure == DongleState.Failure.SetUp -> R.string.dongle_failed_body
                state.failure == DongleState.Failure.Pair -> R.string.dongle_pair_failed_body
                state.board -> R.string.dongle_found_body
                else -> R.string.dongle_none_body
            }
        }

        is DongleState.SettingUp -> {
            R.string.dongle_setting_up_body
        }

        DongleState.Pairing -> {
            R.string.dongle_pairing_body
        }

        DongleState.NeedsBluetooth -> {
            R.string.dongle_bluetooth_body
        }

        DongleState.Searching -> {
            R.string.dongle_searching_body
        }

        DongleState.Connected -> {
            R.string.dongle_connected_body
        }

        is DongleState.Updating -> {
            R.string.dongle_updating_body
        }
    }
