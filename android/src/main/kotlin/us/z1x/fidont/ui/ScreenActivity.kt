package us.z1x.fidont.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

private const val SCREEN = "screen"

enum class Screen { Scan, Settings, Privacy, Licenses, Export }

// each screen is an activity, so the system draws the transitions and the back gesture
class ScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = Screen.valueOf(intent.getStringExtra(SCREEN)!!)
        setScreen {
            when (screen) {
                Screen.Scan -> {
                    Scan(onClose = ::finish)
                }

                Screen.Settings -> {
                    Settings(
                        onBack = ::finish,
                        onPrivacy = { open(Screen.Privacy) },
                        onLicenses = { open(Screen.Licenses) },
                        onExport = { open(Screen.Export) },
                    )
                }

                Screen.Privacy -> {
                    PrivacyScreen(::finish)
                }

                Screen.Licenses -> {
                    LicensesScreen(::finish)
                }

                Screen.Export -> {
                    Export(::finish)
                }
            }
        }
    }
}

fun Context.open(screen: Screen) = startActivity(Intent(this, ScreenActivity::class.java).putExtra(SCREEN, screen.name))

fun ComponentActivity.setScreen(content: @Composable () -> Unit) {
    setContent {
        val dark = rememberDark()
        SideEffect {
            val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            enableEdgeToEdge(bars, bars)
        }
        AppTheme(content)
    }
}
