package us.z1x.fidont.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private const val DURATION = 300
private const val FADE_OUT = 90
private const val NEAR = 0.9f
private const val FAR = 1.1f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Theme {
                var scanning by rememberSaveable { mutableStateOf(false) }
                AnimatedContent(
                    targetState = scanning,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
                    transitionSpec = {
                        val enter = fadeIn(tween(DURATION - FADE_OUT, FADE_OUT)) + scaleIn(tween(DURATION), if (targetState) NEAR else FAR)
                        val exit = fadeOut(tween(FADE_OUT)) + scaleOut(tween(DURATION), if (targetState) FAR else NEAR)
                        enter togetherWith exit
                    },
                    label = "screen",
                ) { scan ->
                    if (scan) Scan(onClose = { scanning = false }) else Home(onScan = { scanning = true })
                }
            }
        }
    }
}
