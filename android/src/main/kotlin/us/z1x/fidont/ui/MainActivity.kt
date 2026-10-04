package us.z1x.fidont.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import us.z1x.fidont.app

private const val DURATION = 300
private const val FADE_OUT = 90
private const val NEAR = 0.9f
private const val FAR = 1.1f

private enum class Screen { Home, Scan, Settings, Privacy, Licenses, Export }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = rememberDark()
            val onboarded by app.preferences.onboarded.collectAsState()
            SideEffect {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(bars, bars)
            }
            AppTheme {
                AnimatedContent(
                    targetState = onboarded,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
                    transitionSpec = { through(forward = true) },
                    label = "onboarding",
                ) { ready ->
                    if (ready) Screens() else Onboarding(onDone = app.preferences::finishOnboarding)
                }
            }
        }
    }
}

@Composable
private fun Screens() {
    var stack by rememberSaveable { mutableStateOf(listOf(Screen.Home)) }
    var swiped by remember { mutableStateOf<Screen?>(null) }
    var swipe by remember { mutableFloatStateOf(0f) }
    val open = { screen: Screen ->
        swipe = 0f
        stack = stack + screen
    }
    val close = {
        swipe = 0f
        stack = stack.dropLast(1)
    }

    PredictiveBackHandler(stack.size > 1) { progress ->
        swiped = stack.last()
        swipe = 0f
        try {
            progress.collect { swipe = it.progress }
            stack = stack.dropLast(1)
        } catch (e: CancellationException) {
            swipe = 0f
            throw e
        }
    }

    AnimatedContent(
        targetState = stack,
        transitionSpec = { through(forward = targetState.size > initialState.size) },
        contentKey = { it.last() },
        label = "screen",
    ) { shown ->
        val screen = shown.last()
        Box(
            Modifier.graphicsLayer {
                // the screen follows the back gesture before it leaves
                val pulled = if (screen == swiped) swipe else 0f
                scaleX = 1f - pulled / 10
                scaleY = 1f - pulled / 10
                shape = RoundedCornerShape((32 * pulled).dp)
                clip = true
            },
        ) {
            when (screen) {
                Screen.Home -> {
                    Home(onScan = { open(Screen.Scan) }, onSettings = { open(Screen.Settings) })
                }

                Screen.Scan -> {
                    Scan(onClose = close)
                }

                Screen.Settings -> {
                    Settings(
                        onBack = close,
                        onPrivacy = { open(Screen.Privacy) },
                        onLicenses = { open(Screen.Licenses) },
                        onExport = { open(Screen.Export) },
                    )
                }

                Screen.Privacy -> {
                    PrivacyScreen(close)
                }

                Screen.Licenses -> {
                    LicensesScreen(close)
                }

                Screen.Export -> {
                    Export(close)
                }
            }
        }
    }
}

private fun through(forward: Boolean): ContentTransform {
    val enter = fadeIn(tween(DURATION - FADE_OUT, FADE_OUT)) + scaleIn(tween(DURATION), if (forward) NEAR else FAR)
    val exit = fadeOut(tween(FADE_OUT)) + scaleOut(tween(DURATION), if (forward) FAR else NEAR)
    return enter togetherWith exit
}
