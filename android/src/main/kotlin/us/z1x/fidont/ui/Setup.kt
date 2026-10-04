package us.z1x.fidont.ui

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import us.z1x.fidont.transport.provider.ProviderService

class Setup(
    val secure: Boolean,
    val provider: Boolean,
    val onLock: () -> Unit,
    val onProvider: () -> Unit,
)

@Composable
fun rememberSetup(): Setup {
    val context = LocalContext.current
    var secure by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf(true) }

    LifecycleResumeEffect(Unit) {
        val service = ComponentName(context, ProviderService::class.java)
        secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        provider = context.getSystemService(android.credentials.CredentialManager::class.java).isEnabledCredentialProviderService(service)
        onPauseOrDispose {}
    }

    return Setup(
        secure = secure,
        provider = provider,
        onLock = {
            val intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            intent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            context.startActivity(intent)
        },
        onProvider = { CredentialManager.create(context).createSettingsPendingIntent().send() },
    )
}
