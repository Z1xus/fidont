package us.z1x.fidont.ui

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.cash.sqldelight.Query
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.store.Credential
import us.z1x.fidont.transport.provider.ProviderService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(
    failed: Boolean,
    onScan: () -> Unit,
) {
    val app = LocalContext.current.app
    val snackbar = remember { SnackbarHostState() }
    val message = stringResource(R.string.scan_failed)
    var deleting by remember { mutableStateOf<Credential?>(null) }
    val credentials by produceState(emptyList<Credential>()) {
        val query = app.credentials.all()
        val listener = Query.Listener { value = query.executeAsList() }
        query.addListener(listener)
        value = query.executeAsList()
        awaitDispose { query.removeListener(listener) }
    }

    LaunchedEffect(failed) {
        if (failed) snackbar.showSnackbar(message)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(painterResource(R.drawable.ic_scan), contentDescription = null) },
                text = { Text(stringResource(R.string.scan)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item { Setup() }
            item { DongleCard() }
            item {
                Text(
                    stringResource(R.string.passkeys),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (credentials.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.passkeys_empty)) },
                        supportingContent = { Text(stringResource(R.string.passkeys_empty_body)) },
                    )
                }
            }
            items(credentials, key = { it.id.toHexString() }) { credential ->
                ListItem(
                    headlineContent = { Text(credential.rpId) },
                    supportingContent = { Text(credential.userName) },
                    trailingContent = {
                        IconButton(onClick = { deleting = credential }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                        }
                    },
                )
            }
        }
    }

    deleting?.let { credential ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title, credential.rpId)) },
            text = { Text(stringResource(R.string.delete_body)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        app.authenticator.remove(credential)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
        )
    }
}

@Composable
private fun Setup() {
    val context = LocalContext.current
    var secure by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        val provider = ComponentName(context, ProviderService::class.java)
        secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        enabled = context.getSystemService(android.credentials.CredentialManager::class.java).isEnabledCredentialProviderService(provider)
        onPauseOrDispose {}
    }
    if (!secure) {
        Notice(R.string.lock_title, R.string.lock_body) {
            val intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            intent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            context.startActivity(intent)
        }
    }
    if (!enabled) {
        Notice(R.string.provider_title, R.string.provider_body) {
            CredentialManager.create(context).createSettingsPendingIntent().send()
        }
    }
}

@Composable
private fun Notice(
    title: Int,
    body: Int,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpen, Modifier.align(Alignment.End)) { Text(stringResource(R.string.open_settings)) }
        }
    }
}
