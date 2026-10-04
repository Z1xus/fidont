package us.z1x.fidont.ui

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.cash.sqldelight.Query
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.store.Credential
import us.z1x.fidont.transport.provider.ProviderService

class HomeState(
    val secure: Boolean,
    val provider: Boolean,
    val credentials: List<Credential>,
    val dongle: DongleState,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(onScan: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    var secure by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<Credential?>(null) }
    var deleting by remember { mutableStateOf<Credential?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    val (dongle, onDongle) = rememberDongle()
    val credentials by produceState(emptyList<Credential>()) {
        val query = app.credentials.all()
        val listener = Query.Listener { value = query.executeAsList() }
        query.addListener(listener)
        value = query.executeAsList()
        awaitDispose { query.removeListener(listener) }
    }

    LifecycleResumeEffect(Unit) {
        val service = ComponentName(context, ProviderService::class.java)
        secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        provider = context.getSystemService(android.credentials.CredentialManager::class.java).isEnabledCredentialProviderService(service)
        onPauseOrDispose {}
    }

    HomeScreen(
        state = HomeState(secure, provider, credentials, dongle),
        onScan = onScan,
        onLock = {
            val intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            intent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            context.startActivity(intent)
        },
        onProvider = { CredentialManager.create(context).createSettingsPendingIntent().send() },
        onPasskey = { opened = it },
        onDongle = { if (it == DongleAction.Forget) forgetting = true else onDongle(it) },
    )

    opened?.let { credential ->
        ModalBottomSheet(onDismissRequest = { opened = null }) {
            PasskeySheet(credential, onDelete = { deleting = credential })
        }
    }

    deleting?.let { credential ->
        Confirm(
            title = stringResource(R.string.delete_title, credential.rpId),
            body = R.string.delete_body,
            action = R.string.delete,
            onDismiss = { deleting = null },
            onConfirm = {
                app.authenticator.remove(credential)
                deleting = null
                opened = null
            },
        )
    }

    if (forgetting) {
        Confirm(
            title = stringResource(R.string.dongle_forget_title),
            body = R.string.dongle_forget_body,
            action = R.string.dongle_forget,
            onDismiss = { forgetting = false },
            onConfirm = {
                onDongle(DongleAction.Forget)
                forgetting = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onScan: () -> Unit,
    onLock: () -> Unit,
    onProvider: () -> Unit,
    onPasskey: (Credential) -> Unit,
    onDongle: (DongleAction) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val list = rememberLazyListState()
    val atTop by remember { derivedStateOf { !list.canScrollBackward } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = scheme.surfaceContainer,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = scheme.surfaceContainer,
                        scrolledContainerColor = scheme.surfaceContainer,
                    ),
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.scan)) },
                icon = { Icon(painterResource(R.drawable.ic_scan), contentDescription = null) },
                onClick = onScan,
                expanded = atTop,
            )
        },
    ) { padding ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            if (!state.secure) {
                item(key = "lock") {
                    Notice(
                        icon = R.drawable.ic_lock,
                        title = R.string.lock_title,
                        body = R.string.lock_body,
                        action = R.string.lock_action,
                        container = scheme.errorContainer,
                        button = scheme.error,
                        onClick = onLock,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (!state.provider) {
                item(key = "provider") {
                    Notice(
                        icon = R.drawable.ic_shield,
                        title = R.string.provider_title,
                        body = R.string.provider_body,
                        action = R.string.provider_action,
                        container = scheme.primaryContainer,
                        button = scheme.primary,
                        onClick = onProvider,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "passkeys") { SectionHeader(R.string.passkeys, Modifier.animateItem()) }
            if (state.credentials.isEmpty()) {
                item(key = "empty") { Empty(Modifier.animateItem()) }
            }
            itemsIndexed(state.credentials, key = { _, credential -> credential.id.toHexString() }) { index, credential ->
                GroupItem(index, state.credentials.size, Modifier.animateItem(), onClick = { onPasskey(credential) }) {
                    ListItem(
                        headlineContent = { Text(credential.rpId) },
                        supportingContent = account(credential)?.let { { Text(it) } },
                        leadingContent = { Avatar(credential.rpId) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item(key = "dongle header") { SectionHeader(R.string.dongle, Modifier.animateItem()) }
            item(key = "dongle") { DongleSection(state.dongle, onDongle, Modifier.animateItem()) }
        }
    }
}

@Composable
fun PasskeySheet(
    credential: Credential,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(credential.rpId, 64.dp)
        Text(credential.rpId, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        account(credential)?.let {
            Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
        ) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = null, Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.delete_passkey))
        }
    }
}

@Composable
private fun Notice(
    icon: Int,
    title: Int,
    body: Int,
    action: Int,
    container: Color,
    button: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(GroupCorner),
        color = container,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Row {
                Icon(painterResource(icon), contentDescription = null, Modifier.padding(top = 2.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = button, contentColor = contentColorFor(button)),
            ) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun Empty(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    GroupItem(0, 1, modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(R.drawable.ic_key, scheme.secondaryContainer, 56.dp)
            Text(
                stringResource(R.string.passkeys_empty),
                Modifier.padding(top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.passkeys_empty_body),
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Confirm(
    title: String,
    body: Int,
    action: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(body)) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(action)) } },
    )
}

private fun account(credential: Credential): String? = credential.userName.ifEmpty { credential.displayName }.ifEmpty { null }
