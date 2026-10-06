package us.z1x.fidont.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.z1x.fidont.BuildConfig
import us.z1x.fidont.Light
import us.z1x.fidont.LightMode
import us.z1x.fidont.R
import us.z1x.fidont.ThemeMode
import us.z1x.fidont.app
import us.z1x.fidont.hybrid.CUSTOM_RELAYS
import us.z1x.fidont.hybrid.FIDONT_RELAY
import us.z1x.fidont.hybrid.GOOGLE_RELAY
import us.z1x.fidont.hybrid.relayDomain

const val AUTHOR = "https://z1x.us"
private const val REPOSITORY = "https://github.com/Z1xus/fidont"
private const val LICENSE = "GPL-3.0"

// the opacity that Material gives to disabled content
private const val DISABLED = 0.38f

private val HUES = (0..360 step 60).map { Color.hsv(it.toFloat(), 1f, 1f) }
private val BRIGHTNESS = 0.1f..1f

@Composable
fun Settings(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = context.app.preferences
    val theme by preferences.theme.collectAsState()
    val black by preferences.black.collectAsState()
    val dynamic by preferences.dynamic.collectAsState()
    val relay by preferences.relay.collectAsState()
    val firmware by preferences.firmware.collectAsState()
    val light by preferences.light.collectAsState()
    val links by context.app.dongle.links
        .collectAsState()
    val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    val dongle = links.any { it.computer == null }
    // the home screen stops its link when this screen opens, and the light needs one
    if (dongle && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
        val lifecycle = LocalLifecycleOwner.current
        LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { context.app.dongle.serve() } }
    }
    SettingsScreen(
        theme = theme,
        black = black,
        dynamic = dynamic,
        relay = relay,
        firmware = firmware,
        light = light.takeIf { dongle },
        version = BuildConfig.VERSION_NAME,
        commit = BuildConfig.COMMIT,
        strongBox = strongBox,
        setup = rememberSetup(),
        backup = rememberBackup(onExport),
        onTheme = preferences::setTheme,
        onBlack = preferences::setBlack,
        onDynamic = preferences::setDynamic,
        onRelay = preferences::setRelay,
        onFirmware = preferences::setFirmware,
        onLight = preferences::setLight,
        onBack = onBack,
        onPrivacy = onPrivacy,
        onLicenses = onLicenses,
    )
}

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    black: Boolean,
    dynamic: Boolean,
    relay: Int,
    firmware: String,
    light: Light?,
    version: String,
    commit: String,
    strongBox: Boolean,
    setup: Setup,
    backup: Backup,
    onTheme: (ThemeMode) -> Unit,
    onBlack: (Boolean) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onRelay: (Int) -> Unit,
    onFirmware: (String) -> Unit,
    onLight: (Light) -> Unit,
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
        item { SectionHeader(R.string.backup) }
        item {
            Entry(
                0,
                4,
                stringResource(R.string.backup_allow),
                supporting = stringResource(R.string.backup_allow_body),
                trailing = { Switch(backup.enabled, onCheckedChange = null) },
                onClick = backup.onToggle,
            )
        }
        item {
            Entry(
                1,
                4,
                stringResource(R.string.automatic),
                modifier = Modifier.alpha(if (backup.enabled) 1f else DISABLED),
                supporting = stringResource(if (backup.failed) R.string.automatic_failed else R.string.automatic_body),
                trailing = { Switch(backup.automatic, onCheckedChange = null) },
                onClick = backup.onAutomatic.takeIf { backup.enabled },
            )
        }
        item {
            Entry(
                2,
                4,
                stringResource(R.string.export),
                modifier = Modifier.alpha(if (backup.enabled) 1f else DISABLED),
                supporting = stringResource(R.string.export_body),
                onClick = backup.onExport.takeIf { backup.enabled },
            )
        }
        item {
            Entry(
                3,
                4,
                stringResource(R.string.import_action),
                supporting = stringResource(R.string.import_body),
                onClick = backup.onImport,
            )
        }
        item { SectionHeader(R.string.relay) }
        item { Relay(relay, onRelay) }
        item { SectionHeader(R.string.firmware) }
        item { Firmware(firmware, onFirmware) }
        item { SectionHeader(R.string.appearance) }
        item {
            GroupItem(0, 3) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = mode == theme,
                                onClick = { onTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                icon = {},
                            ) { Text(stringResource(labels[index])) }
                        }
                    }
                }
            }
        }
        item {
            Entry(
                1,
                3,
                stringResource(R.string.pure_black),
                supporting = stringResource(R.string.pure_black_body),
                trailing = { Switch(black, onCheckedChange = null) },
                onClick = { onBlack(!black) },
            )
        }
        item {
            Entry(
                2,
                3,
                stringResource(R.string.material_you),
                supporting = stringResource(R.string.material_you_body),
                trailing = { Switch(dynamic, onCheckedChange = null) },
                onClick = { onDynamic(!dynamic) },
            )
        }
        if (light != null) {
            item { SectionHeader(R.string.`fun`) }
            item { Light(light, onLight) }
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
        item {
            Entry(
                5,
                6,
                stringResource(R.string.version),
                supporting = "$version ($commit)",
                onClick = { links.openUri("$REPOSITORY/commit/$commit") },
            )
        }
    }
}

@Composable
private fun Relay(
    relay: Int,
    onRelay: (Int) -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf(relay != FIDONT_RELAY && relay != GOOGLE_RELAY) }
    var id by rememberSaveable { mutableStateOf(if (custom) relay.toString() else "") }
    val valid = id.toIntOrNull()?.takeIf { it in CUSTOM_RELAYS }
    val count = if (custom) 4 else 3
    Column {
        Entry(
            0,
            count,
            stringResource(R.string.relay_fidont),
            supporting = stringResource(R.string.relay_fidont_body),
            trailing = { RadioButton(!custom && relay == FIDONT_RELAY, onClick = null) },
            onClick = {
                custom = false
                onRelay(FIDONT_RELAY)
            },
        )
        Entry(
            1,
            count,
            stringResource(R.string.relay_google),
            supporting = stringResource(R.string.relay_google_body),
            trailing = { RadioButton(!custom && relay == GOOGLE_RELAY, onClick = null) },
            onClick = {
                custom = false
                onRelay(GOOGLE_RELAY)
            },
        )
        Entry(
            2,
            count,
            stringResource(R.string.relay_custom),
            supporting = stringResource(R.string.relay_custom_body),
            trailing = { RadioButton(custom, onClick = null) },
            onClick = {
                custom = true
                valid?.let(onRelay)
            },
        )
        if (custom) {
            GroupItem(3, count) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { text ->
                        id = text.filter(Char::isDigit).take(5)
                        id.toIntOrNull()?.takeIf { it in CUSTOM_RELAYS }?.let(onRelay)
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                    label = { Text(stringResource(R.string.relay_id)) },
                    supportingText = { Text(valid?.let(::relayDomain) ?: stringResource(R.string.relay_id_body)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
        Text(
            stringResource(R.string.relay_body),
            Modifier.padding(start = 32.dp, top = 8.dp, end = 32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Firmware(
    firmware: String,
    onFirmware: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(firmware) }
    Column {
        GroupItem(0, 1) {
            OutlinedTextField(
                value = url,
                onValueChange = { text ->
                    url = text.trim()
                    if (url.isEmpty() || url.toHttpUrlOrNull() != null) onFirmware(url)
                },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                label = { Text(stringResource(R.string.firmware_url)) },
                supportingText = { Text(stringResource(R.string.firmware_url_body)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
        }
        Text(
            stringResource(R.string.firmware_body),
            Modifier.padding(start = 32.dp, top = 8.dp, end = 32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Light(
    light: Light,
    onLight: (Light) -> Unit,
) {
    val labels = listOf(R.string.light_off, R.string.light_requests, R.string.light_on, R.string.light_rainbow)
    val count = if (light.mode == LightMode.Off) 4 else 5
    Column {
        LightMode.entries.forEachIndexed { index, mode ->
            Entry(
                index,
                count,
                stringResource(labels[index]),
                supporting = stringResource(R.string.light_requests_body).takeIf { mode == LightMode.Requests },
                trailing = { RadioButton(mode == light.mode, onClick = null) },
                onClick = { onLight(light.copy(mode = mode)) },
            )
        }
        if (light.mode != LightMode.Off) {
            GroupItem(4, count) {
                Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    if (light.mode != LightMode.Rainbow) {
                        Text(stringResource(R.string.light_color), style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            value = light.hue,
                            onValueChange = { onLight(light.copy(hue = it)) },
                            valueRange = 0f..360f,
                            track = { Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.horizontalGradient(HUES), CircleShape)) },
                        )
                    }
                    Text(stringResource(R.string.light_brightness), style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = light.brightness,
                        onValueChange = { onLight(light.copy(brightness = it)) },
                        valueRange = BRIGHTNESS,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.light_body),
            Modifier.padding(start = 32.dp, top = 8.dp, end = 32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
