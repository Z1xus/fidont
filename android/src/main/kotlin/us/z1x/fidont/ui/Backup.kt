package us.z1x.fidont.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.z1x.fidont.App
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.backupEncrypted
import us.z1x.fidont.decodeBackup
import java.security.GeneralSecurityException

class Backup(
    val enabled: Boolean,
    val automatic: Boolean,
    val failed: Boolean,
    val onToggle: () -> Unit,
    val onAutomatic: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
)

private sealed interface Dialog {
    data object Off : Dialog

    data object Automatic : Dialog

    class Import(
        val file: ByteArray,
    ) : Dialog
}

@Composable
fun rememberBackup(onExport: () -> Unit): Backup {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(app.keys.backup) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val file by app.preferences.backupFile.collectAsState()
    val failed by app.preferences.backupFailed.collectAsState()
    var password by remember { mutableStateOf("") }
    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                scope.launch {
                    if (!app.keys.setPassword(password)) return@launch
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    app.preferences.setBackupFile(uri.toString())
                    app.saveBackup()
                }
            }
        }

    fun restore(
        file: ByteArray,
        password: String,
    ) {
        scope.launch {
            val count = withContext(Dispatchers.Default) { restore(app, file, password) }
            val text =
                if (count == null) {
                    resources.getString(R.string.import_failed)
                } else {
                    resources.getQuantityString(R.plurals.imported, count, count)
                }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                    if (backupEncrypted(file) == true) dialog = Dialog.Import(file) else restore(file, "")
                }
            }
        }

    when (val shown = dialog) {
        Dialog.Off -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.backup_off_title)) },
                text = { Text(stringResource(R.string.backup_off_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog = null
                            scope.launch {
                                app.keys.setBackup(false)
                                app.preferences.setBackupFile(null)
                                enabled = false
                            }
                        },
                    ) { Text(stringResource(R.string.backup_off)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        Dialog.Automatic -> {
            PasswordDialog(R.string.automatic_title, R.string.automatic_choose, { dialog = null }) {
                password = it
                create.launch(BACKUP_FILE_NAME)
            }
        }

        is Dialog.Import -> {
            PasswordDialog(R.string.import_title, R.string.import_action, { dialog = null }) { restore(shown.file, it) }
        }

        null -> {}
    }

    return Backup(
        enabled = enabled,
        automatic = file != null,
        failed = failed,
        onToggle = {
            if (enabled) {
                dialog = Dialog.Off
            } else {
                scope.launch {
                    app.keys.setBackup(true)
                    enabled = app.keys.backup
                }
            }
        },
        onAutomatic = {
            if (file == null) {
                dialog = Dialog.Automatic
            } else {
                app.keys.forgetPassword()
                app.preferences.setBackupFile(null)
            }
        },
        onExport = onExport,
        onImport = { open.launch(arrayOf("*/*")) },
    )
}

@Composable
private fun PasswordDialog(
    title: Int,
    action: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(password)
                },
                enabled = password.isNotEmpty(),
            ) { Text(stringResource(action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun restore(
    app: App,
    file: ByteArray,
    password: String,
): Int? {
    if (!app.keys.secure) return null
    val entries = decodeBackup(file, password) ?: return null
    val fresh = entries.filter { app.credentials.byId(it.credential.id).executeAsOneOrNull() == null }
    try {
        fresh.forEach {
            app.keys.restore(it.credential.id, it.key, it.publicKey, it.secret)
            app.credentials.insert(it.credential)
        }
    } catch (_: GeneralSecurityException) {
        return null
    }
    return fresh.size
}
