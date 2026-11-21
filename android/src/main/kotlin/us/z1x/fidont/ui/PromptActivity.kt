package us.z1x.fidont.ui

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.z1x.fidont.R
import us.z1x.fidont.store.Credential
import us.z1x.fidont.transport.dongle.requestNotification
import java.security.Signature
import java.util.UUID
import javax.crypto.Cipher
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private object NotifyRequest : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<NotifyRequest>
}

private const val PROMPT_NOTIFICATION = 2

private sealed class Request {
    val token = "fidont:prompt/${UUID.randomUUID()}".toUri()
    val result = CompletableDeferred<Any?>()
    var owner: PromptActivity? = null

    class Authenticate(
        val title: String,
        val subtitle: String?,
        val crypto: BiometricPrompt.CryptoObject,
    ) : Request()

    class Choose(
        val credentials: List<Credential>,
    ) : Request()
}

class PromptActivity : ComponentActivity() {
    private val cancellation = CancellationSignal()
    private var shown: Request? = null
    private var completion: DisposableHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = request?.takeIf { it.token == intent.data && !it.result.isCompleted }
        if (request == null) {
            intent.data?.let { getSystemService(NotificationManager::class.java).cancel(it.toString(), PROMPT_NOTIFICATION) }
            return finish()
        }
        // a notification tap can race the automatic launch
        request.owner?.let { owner ->
            getSystemService(ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo?.taskId == owner.taskId }?.moveToFront()
            return finish()
        }
        request.owner = this
        shown = request
        completion = request.result.invokeOnCompletion {
            runOnUiThread {
                cancellation.cancel()
                finish()
            }
        }
        when (request) {
            is Request.Authenticate -> authenticate(request)
            is Request.Choose -> setContent { AppTheme { Chooser(request) } }
        }
    }

    override fun onDestroy() {
        completion?.dispose()
        shown?.takeIf { it.owner === this }?.let {
            it.owner = null
            if (!isChangingConfigurations) it.result.complete(null)
        }
        cancellation.cancel()
        super.onDestroy()
    }

    private fun authenticate(request: Request.Authenticate) {
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (request.owner === this@PromptActivity) request.result.complete(result.cryptoObject)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (request.owner === this@PromptActivity) request.result.complete(null)
                }
            }
        BiometricPrompt
            .Builder(this)
            .setTitle(request.title)
            .apply { request.subtitle?.let(::setSubtitle) }
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(request.crypto, cancellation, mainExecutor, callback)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Chooser(request: Request.Choose) {
        ModalBottomSheet(onDismissRequest = { request.result.complete(null) }) {
            Text(
                stringResource(R.string.prompt_choose),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                request.credentials.first().rpId,
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            request.credentials.forEach { credential ->
                ListItem(
                    headlineContent = { Text(credential.userName) },
                    supportingContent = credential.displayName.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                    leadingContent = { Avatar(credential.userName) },
                    modifier = Modifier.clickable { request.result.complete(credential) }.padding(horizontal = 8.dp),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        // the activity and caller share this state on the main thread
        private var request: Request? = null

        suspend fun <T> withNotification(block: suspend () -> T): T = withContext(NotifyRequest) { block() }

        suspend fun authenticate(
            context: Context,
            credential: Credential,
            registering: Boolean,
            signature: Signature,
        ): Signature? {
            val title = context.getString(if (registering) R.string.prompt_create else R.string.prompt_sign_in, credential.rpId)
            return unlock(context, Request.Authenticate(title, credential.userName, BiometricPrompt.CryptoObject(signature)))?.signature
        }

        suspend fun unlock(
            context: Context,
            title: Int,
            cipher: Cipher,
        ): Cipher? = unlock(context, Request.Authenticate(context.getString(title), null, BiometricPrompt.CryptoObject(cipher)))?.cipher

        private suspend fun unlock(
            context: Context,
            request: Request.Authenticate,
        ) = show(context, request) as BiometricPrompt.CryptoObject?

        suspend fun choose(
            context: Context,
            credentials: List<Credential>,
        ): Credential? = show(context, Request.Choose(credentials)) as Credential?

        private suspend fun show(
            context: Context,
            request: Request,
        ): Any? = withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                val intent = Intent(context, PromptActivity::class.java)
                    .setData(request.token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val notifications = context.getSystemService(NotificationManager::class.java)
                val open = if (currentCoroutineContext()[NotifyRequest.Key] != null) {
                    PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                } else null
                this@Companion.request = request
                try {
                    open?.let { notifications.notify(request.token.toString(), PROMPT_NOTIFICATION, requestNotification(context, it)) }
                    try {
                        context.startActivity(intent)
                    } catch (error: SecurityException) {
                        // a blocked background launch still leaves the notification usable
                        if (open == null) throw error
                    }
                    request.result.await()
                } finally {
                    this@Companion.request = null
                    open?.cancel()
                    notifications.cancel(request.token.toString(), PROMPT_NOTIFICATION)
                    request.result.cancel()
                }
            }
        }
    }
}
