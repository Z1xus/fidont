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

enum class ThemeMode { System, Light, Dark }

class Preferences(
    context: Context,
) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = MutableStateFlow(ThemeMode.valueOf(store.getString(THEME, null) ?: ThemeMode.System.name))
    val black = MutableStateFlow(store.getBoolean(BLACK, false))
    val dynamic = MutableStateFlow(store.getBoolean(DYNAMIC, false))
    val onboarded = MutableStateFlow(store.getBoolean(ONBOARDED, false))
    val relay = MutableStateFlow(store.getInt(RELAY, FIDONT_RELAY))

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

    fun finishOnboarding() {
        store.edit { putBoolean(ONBOARDED, true) }
        onboarded.value = true
    }
}
