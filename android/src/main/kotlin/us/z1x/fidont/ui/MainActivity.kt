package us.z1x.fidont.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import us.z1x.fidont.app

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen {
            val onboarded by app.preferences.onboarded.collectAsState()
            Crossfade(
                targetState = onboarded,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
                label = "onboarding",
            ) { ready ->
                if (ready) {
                    Home(onScan = { open(Screen.Scan) }, onSettings = { open(Screen.Settings) })
                } else {
                    Onboarding(onDone = app.preferences::finishOnboarding)
                }
            }
        }
    }
}
