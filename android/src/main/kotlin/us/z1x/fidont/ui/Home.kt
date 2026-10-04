package us.z1x.fidont.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.Query
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.store.Credential

class HomeState(
    val secure: Boolean,
    val provider: Boolean,
    val credentials: List<Credential>,
    val dongle: DongleState,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val setup = rememberSetup()
    var opened by remember { mutableStateOf<Credential?>(null) }
    var deleting by remember { mutableStateOf<Credential?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    var dongleOpen by remember { mutableStateOf(false) }
    val (dongle, onDongle) = rememberDongle()
    val board = (dongle as? DongleState.Unpaired)?.board == true
    val credentials by produceState(emptyList<Credential>()) {
        val query = app.credentials.all()
        val listener = Query.Listener { value = query.executeAsList() }
        query.addListener(listener)
        value = query.executeAsList()
        awaitDispose { query.removeListener(listener) }
    }

    LaunchedEffect(board) {
        if (board) dongleOpen = true
    }
    HomeScreen(
        state = HomeState(setup.secure, setup.provider, credentials, dongle),
        onScan = onScan,
        onSettings = onSettings,
        onLock = setup.onLock,
        onProvider = setup.onProvider,
        onPasskey = { opened = it },
        onDongle = { dongleOpen = true },
    )

    if (dongleOpen) {
        ModalBottomSheet(onDismissRequest = { dongleOpen = false }) {
            DongleSheet(dongle) { if (it == DongleAction.Forget) forgetting = true else onDongle(it) }
        }
    }

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
            action = R.string.forget,
            onDismiss = { forgetting = false },
            onConfirm = {
                onDongle(DongleAction.Forget)
                forgetting = false
                dongleOpen = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onProvider: () -> Unit,
    onPasskey: (Credential) -> Unit,
    onDongle: () -> Unit,
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
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                },
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
            val steps = listOf(state.secure, state.provider).count { !it }
            if (steps > 0) {
                item(key = "setup") { SectionHeader(R.string.setup, Modifier.animateItem()) }
            }
            if (!state.secure) {
                item(key = "lock") {
                    Step(R.drawable.ic_lock, R.string.lock_title, R.string.lock_body, false, 0, steps, onLock, Modifier.animateItem())
                }
            }
            if (!state.provider) {
                item(key = "provider") {
                    Step(
                        icon = R.drawable.ic_shield,
                        title = R.string.provider_title,
                        body = R.string.provider_body,
                        done = false,
                        index = steps - 1,
                        count = steps,
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
                Entry(
                    index = index,
                    count = state.credentials.size,
                    headline = credential.rpId,
                    modifier = Modifier.animateItem(),
                    supporting = account(credential),
                    leading = { Avatar(credential.rpId) },
                    onClick = { onPasskey(credential) },
                )
            }
            item(key = "dongle header") { SectionHeader(R.string.dongle, Modifier.animateItem()) }
            item(key = "dongle") { DongleRow(state.dongle, onDongle, Modifier.animateItem()) }
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
