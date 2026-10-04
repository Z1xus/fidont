package us.z1x.fidont.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

    enum class Failure { SetUp, Pair }
}

enum class DongleAction { SetUp, Pair, Allow, Forget }

@Composable
fun DongleSection(
    state: DongleState,
    onAction: (DongleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, body) =
        when (state) {
            is DongleState.Unpaired -> {
                when {
                    state.failure == DongleState.Failure.SetUp -> R.string.dongle_failed to R.string.dongle_failed_body
                    state.failure == DongleState.Failure.Pair -> R.string.dongle_pair_failed to R.string.dongle_pair_failed_body
                    state.board -> R.string.dongle_found to R.string.dongle_found_body
                    else -> R.string.dongle_none to R.string.dongle_none_body
                }
            }

            is DongleState.SettingUp -> {
                R.string.dongle_setting_up to R.string.dongle_setting_up_body
            }

            DongleState.Pairing -> {
                R.string.dongle_pairing to R.string.dongle_pairing_body
            }

            DongleState.NeedsBluetooth -> {
                R.string.dongle_bluetooth to R.string.dongle_bluetooth_body
            }

            DongleState.Searching -> {
                R.string.dongle_searching to R.string.dongle_searching_body
            }

            DongleState.Connected -> {
                R.string.dongle_connected to R.string.dongle_connected_body
            }

            is DongleState.Updating -> {
                R.string.dongle_updating to R.string.dongle_updating_body
            }
        }
    val scheme = MaterialTheme.colorScheme
    val paired = state == DongleState.Searching || state == DongleState.Connected
    val buttons = state is DongleState.Unpaired || state == DongleState.NeedsBluetooth
    val badge =
        when {
            state == DongleState.Connected -> scheme.primaryContainer
            state is DongleState.Unpaired && state.failure != null -> scheme.errorContainer
            else -> scheme.surfaceContainerHighest
        }

    GroupItem(0, 1, modifier) {
        Column(Modifier.animateContentSize().padding(top = 16.dp, bottom = if (buttons) 8.dp else 16.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(R.drawable.ic_usb, badge)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    AnimatedContent(title, label = "title") {
                        Text(stringResource(it), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(body), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (paired) {
                    TextButton(onClick = { onAction(DongleAction.Forget) }) { Text(stringResource(R.string.dongle_forget)) }
                }
            }
            when (state) {
                is DongleState.SettingUp -> {
                    Progress(state.progress)
                }

                is DongleState.Updating -> {
                    Progress(state.progress)
                }

                DongleState.Pairing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp))
                }

                is DongleState.Unpaired -> {
                    Row(Modifier.align(Alignment.End).padding(top = 4.dp, end = 16.dp)) {
                        TextButton(onClick = { onAction(DongleAction.Pair) }) { Text(stringResource(R.string.dongle_pair)) }
                        if (state.board) {
                            Button(onClick = { onAction(DongleAction.SetUp) }, Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.dongle_set_up))
                            }
                        }
                    }
                }

                DongleState.NeedsBluetooth -> {
                    Row(Modifier.align(Alignment.End).padding(top = 4.dp, end = 16.dp)) {
                        TextButton(onClick = { onAction(DongleAction.Forget) }) { Text(stringResource(R.string.dongle_forget)) }
                        Button(onClick = { onAction(DongleAction.Allow) }, Modifier.padding(start = 8.dp)) {
                            Text(stringResource(R.string.allow))
                        }
                    }
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun Progress(progress: Float) {
    val animated by animateFloatAsState(progress, label = "progress")
    LinearProgressIndicator(progress = { animated }, Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp))
}
