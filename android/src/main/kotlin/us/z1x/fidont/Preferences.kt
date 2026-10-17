package us.z1x.fidont

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import us.z1x.fidont.hybrid.FIDONT_RELAY

private const val THEME = "theme"
private const val BLACK = "black"
private const val DYNAMIC = "dynamic"
private const val ONBOARDED = "onboarded"
private const val RELAY = "relay"
private const val FIRMWARE = "firmware"
private const val BACKUP_FILE = "backup_file"
private const val BACKUP_FAILED = "backup_failed"
private const val LIGHT_MODE = "light_mode"
private const val LIGHT_HUE = "light_hue"
private const val LIGHT_BRIGHTNESS = "light_brightness"
private const val BACKGROUND_BLUETOOTH_ASSOCIATION = "background_bluetooth_association"
private const val BACKGROUND_BLUETOOTH = "background_bluetooth"

enum class ThemeMode { System, Light, Dark }

enum class BackgroundBluetoothMode { Off, Always, Companion }

// the firmware knows the modes by their order
enum class LightMode { Off, Requests, On, Rainbow }

data class Light(
    val mode: LightMode,
    val hue: Float,
    val brightness: Float,
)

class Preferences(
    context: Context,
) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = MutableStateFlow(ThemeMode.valueOf(store.getString(THEME, null) ?: ThemeMode.System.name))
    val black = MutableStateFlow(store.getBoolean(BLACK, false))
    val dynamic = MutableStateFlow(store.getBoolean(DYNAMIC, false))
    val onboarded = MutableStateFlow(store.getBoolean(ONBOARDED, false))
    val relay = MutableStateFlow(store.getInt(RELAY, FIDONT_RELAY))
    val firmware = MutableStateFlow(store.getString(FIRMWARE, "")!!)
    val backupFile = MutableStateFlow(store.getString(BACKUP_FILE, null))
    val backupFailed = MutableStateFlow(store.getBoolean(BACKUP_FAILED, false))
    val backgroundBluetoothAssociation =
        MutableStateFlow(store.getInt(BACKGROUND_BLUETOOTH_ASSOCIATION, -1).takeIf { it >= 0 })
    val backgroundBluetooth =
        MutableStateFlow(
            runCatching {
                BackgroundBluetoothMode.valueOf(store.getString(BACKGROUND_BLUETOOTH, null) ?: BackgroundBluetoothMode.Off.name)
            }.getOrDefault(BackgroundBluetoothMode.Off),
        )
    val light =
        MutableStateFlow(
            Light(
                LightMode.valueOf(store.getString(LIGHT_MODE, null) ?: LightMode.Requests.name),
                store.getFloat(LIGHT_HUE, 160f),
                store.getFloat(LIGHT_BRIGHTNESS, 0.5f),
            ),
        )

    fun setTheme(mode: ThemeMode) {
        store.edit { putString(THEME, mode.name) }
        theme.value = mode
    }

    fun setBlack(value: Boolean) {
        store.edit { putBoolean(BLACK, value) }
        black.value = value
    }

    fun setDynamic(value: Boolean) {
        store.edit { putBoolean(DYNAMIC, value) }
        dynamic.value = value
    }

    fun setRelay(id: Int) {
        store.edit { putInt(RELAY, id) }
        relay.value = id
    }

    fun setFirmware(url: String) {
        store.edit { putString(FIRMWARE, url) }
        firmware.value = url
    }

    fun setBackupFile(uri: String?) {
        store.edit { putString(BACKUP_FILE, uri) }
        backupFile.value = uri
        setBackupFailed(false)
    }

    fun setBackupFailed(value: Boolean) {
        store.edit { putBoolean(BACKUP_FAILED, value) }
        backupFailed.value = value
    }

    fun setBackgroundBluetoothAssociation(value: Int?) {
        store.edit {
            if (value == null) remove(BACKGROUND_BLUETOOTH_ASSOCIATION) else putInt(BACKGROUND_BLUETOOTH_ASSOCIATION, value)
        }
        backgroundBluetoothAssociation.value = value
    }

    fun setBackgroundBluetooth(value: BackgroundBluetoothMode) {
        store.edit { putString(BACKGROUND_BLUETOOTH, value.name) }
        backgroundBluetooth.value = value
    }

    fun setLight(value: Light) {
        store.edit {
            putString(LIGHT_MODE, value.mode.name)
            putFloat(LIGHT_HUE, value.hue)
            putFloat(LIGHT_BRIGHTNESS, value.brightness)
        }
        light.value = value
    }

    fun finishOnboarding() {
        store.edit { putBoolean(ONBOARDED, true) }
        onboarded.value = true
    }
}
