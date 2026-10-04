package us.z1x.fidont.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.z1x.fidont.R
import us.z1x.fidont.ThemeMode
import us.z1x.fidont.app

const val AUTHOR = "https://z1x.us"
private const val REPOSITORY = "https://github.com/Z1xus/fidont"
private const val LICENSE = "GPL-3.0"

@Composable
fun Settings(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = context.app.preferences
    val theme by preferences.theme.collectAsState()
    val version =
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
    val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    SettingsScreen(theme, version, strongBox, rememberSetup(), preferences::setTheme, onBack, onPrivacy, onLicenses)
}

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    version: String,
    strongBox: Boolean,
    setup: Setup,
    onTheme: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
) {
    val links = LocalUriHandler.current
    val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
    Page(R.string.settings, onBack) {
        item { SectionHeader(R.string.security) }
        item {
            val storage = if (strongBox) R.string.storage_strongbox else R.string.storage_tee
            Step(R.drawable.ic_key, R.string.storage, storage, true, 0, 3, {})
        }
        item {
            val state = if (setup.secure) R.string.on else R.string.screen_lock_off
            Step(R.drawable.ic_lock, R.string.screen_lock, state, setup.secure, 1, 3, setup.onLock)
        }
        item {
            val state = if (setup.provider) R.string.on else R.string.off
            Step(R.drawable.ic_shield, R.string.provider, state, setup.provider, 2, 3, setup.onProvider)
        }
        item { SectionHeader(R.string.appearance) }
        item {
            GroupItem(0, 1) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = mode == theme,
                                onClick = { onTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            ) { Text(stringResource(labels[index])) }
                        }
                    }
                }
            }
        }
        item { SectionHeader(R.string.about) }
        item { Entry(0, 6, stringResource(R.string.privacy), onClick = onPrivacy) }
        item {
            Entry(
                1,
                6,
                stringResource(R.string.license),
                supporting = LICENSE,
                onClick = { links.openUri("$REPOSITORY/blob/main/LICENSE") },
            )
        }
        item { Entry(2, 6, stringResource(R.string.licenses), onClick = onLicenses) }
        item {
            Entry(
                3,
                6,
                stringResource(R.string.source),
                supporting = REPOSITORY.removePrefix("https://"),
                onClick = { links.openUri(REPOSITORY) },
            )
        }
        item {
            Entry(
                4,
                6,
                stringResource(R.string.author),
                supporting = AUTHOR.removePrefix("https://"),
                onClick = { links.openUri(AUTHOR) },
            )
        }
        item { Entry(5, 6, stringResource(R.string.version), supporting = version) }
    }
}
