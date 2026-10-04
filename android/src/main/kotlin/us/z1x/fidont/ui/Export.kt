package us.z1x.fidont.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.encodeBackup
import us.z1x.fidont.store.Credential

private const val FILE_NAME = "passkeys.fidont"

@Composable
fun Export(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val (ready, kept) =
        remember {
            app.credentials
                .all()
                .executeAsList()
                .partition { app.keys.exportable(it.id) }
        }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf<Pair<ByteArray, Int>?>(null) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val (file, count) = exported ?: return@rememberLauncherForActivityResult
            exported = null
            if (uri != null) {
                scope.launch {
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(file) } }
                    Toast.makeText(context, resources.getQuantityString(R.plurals.exported, count, count), Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }

    ExportScreen(
        ready = ready,
        kept = kept,
        encrypt = encrypt,
        password = password,
        onEncrypt = { encrypt = it },
        onPassword = { password = it },
        onBack = onBack,
        onExport = {
            scope.launch {
                val entries = app.keys.export(ready) ?: return@launch
                exported = withContext(Dispatchers.Default) { encodeBackup(entries, if (encrypt) password else "") } to entries.size
                save.launch(FILE_NAME)
            }
        },
    )
}

@Composable
fun ExportScreen(
    ready: List<Credential>,
    kept: List<Credential>,
    encrypt: Boolean,
    password: String,
    onEncrypt: (Boolean) -> Unit,
    onPassword: (String) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Page(
        title = R.string.export_title,
        onBack = onBack,
        bottomBar = {
            if (ready.isNotEmpty()) {
                Surface(color = scheme.surfaceContainer) {
                    Button(
                        onClick = onExport,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        enabled = !encrypt || password.isNotEmpty(),
                    ) { Text(stringResource(R.string.export)) }
                }
            }
        },
    ) {
        if (ready.isEmpty()) {
            item { Empty(R.string.export_empty, R.string.export_empty_body, Modifier.padding(top = 24.dp)) }
        } else {
            item { SectionHeader(R.string.export_protection) }
            item {
                Column {
                    Entry(
                        0,
                        if (encrypt) 2 else 1,
                        stringResource(R.string.export_encrypt),
                        supporting = if (encrypt) null else stringResource(R.string.export_plain_body),
                        trailing = { Switch(encrypt, onCheckedChange = null) },
                        onClick = { onEncrypt(!encrypt) },
                    )
                    AnimatedVisibility(encrypt) {
                        GroupItem(1, 2) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = onPassword,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                label = { Text(stringResource(R.string.password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                        }
                    }
                }
            }
            item { SectionHeader(R.string.export_included) }
            passkeys(ready)
        }
        if (kept.isNotEmpty()) {
            item { SectionHeader(R.string.export_kept) }
            passkeys(kept)
            if (ready.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.export_kept_body),
                        Modifier.padding(start = 32.dp, top = 8.dp, end = 32.dp),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.passkeys(credentials: List<Credential>) {
    itemsIndexed(credentials, key = { _, credential -> credential.id.toHexString() }) { index, credential ->
        PasskeyEntry(index, credentials.size, credential)
    }
}
