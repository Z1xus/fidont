package us.z1x.fidont.ui

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.z1x.fidont.R
import us.z1x.fidont.store.Credential
import java.security.Signature

private sealed class Request {
    val result = CompletableDeferred<Any?>()

    class Authenticate(
        val credential: Credential,
        val registering: Boolean,
        val signature: Signature,
    ) : Request()

    class Choose(
        val credentials: List<Credential>,
    ) : Request()
}

class PromptActivity : ComponentActivity() {
    private val cancellation = CancellationSignal()
    private var shown: Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = request ?: return finish()
        shown = request
        request.result.invokeOnCompletion { runOnUiThread(::finish) }
        when (request) {
            is Request.Authenticate -> authenticate(request)
            is Request.Choose -> setContent { Theme { Chooser(request) } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancellation.cancel()
        shown?.result?.complete(null)
    }

    private fun authenticate(request: Request.Authenticate) {
        val title = if (request.registering) R.string.prompt_create else R.string.prompt_sign_in
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    request.result.complete(result.cryptoObject.signature)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    request.result.complete(null)
                }
            }
        BiometricPrompt
            .Builder(this)
            .setTitle(getString(title, request.credential.rpId))
            .setSubtitle(request.credential.userName)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(BiometricPrompt.CryptoObject(request.signature), cancellation, mainExecutor, callback)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Chooser(request: Request.Choose) {
        ModalBottomSheet(onDismissRequest = { request.result.complete(null) }) {
            Text(
                stringResource(R.string.prompt_choose),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            request.credentials.forEach { credential ->
                ListItem(
                    headlineContent = { Text(credential.userName) },
                    supportingContent = { Text(credential.displayName) },
                    modifier = Modifier.clickable { request.result.complete(credential) },
                )
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        private var request: Request? = null

        suspend fun authenticate(
            context: Context,
            credential: Credential,
            registering: Boolean,
            signature: Signature,
        ): Signature? = show(context, Request.Authenticate(credential, registering, signature)) as Signature?

        suspend fun choose(
            context: Context,
            credentials: List<Credential>,
        ): Credential? = show(context, Request.Choose(credentials)) as Credential?

        private suspend fun show(
            context: Context,
            request: Request,
        ): Any? =
            mutex.withLock {
                this.request = request
                context.startActivity(Intent(context, PromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    request.result.await()
                } finally {
                    request.result.cancel()
                    this.request = null
                }
            }
    }
}
