package us.z1x.fidont.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import us.z1x.fidont.ThemeMode
import us.z1x.fidont.app

@Composable
fun Theme(
    dark: Boolean = isSystemInDarkTheme(),
    black: Boolean = false,
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        when {
            !dynamic -> if (dark) DarkColors else LightColors
            dark -> dynamicDarkColorScheme(context)
            else -> dynamicLightColorScheme(context)
        }
    MaterialTheme(
        colorScheme =
            if (dark && black) {
                scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color.Black)
            } else {
                scheme
            },
        content = content,
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val preferences = LocalContext.current.app.preferences
    val black by preferences.black.collectAsState()
    val dynamic by preferences.dynamic.collectAsState()
    Theme(rememberDark(), black, dynamic, content)
}

@Composable
fun rememberDark(): Boolean {
    val mode by LocalContext.current.app.preferences.theme
        .collectAsState()
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}
