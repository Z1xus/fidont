package us.z1x.fidont.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Theme {
                var scanning by rememberSaveable { mutableStateOf(false) }
                var failed by remember { mutableStateOf(false) }
                if (scanning) {
                    Scan(
                        onClose = { scanning = false },
                        onFailed = {
                            scanning = false
                            failed = true
                        },
                    )
                } else {
                    Home(
                        failed = failed,
                        onScan = {
                            failed = false
                            scanning = true
                        },
                    )
                }
            }
        }
    }
}
