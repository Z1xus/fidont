package us.z1x.fidont

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import us.z1x.fidont.ctap2.Authenticator
import us.z1x.fidont.keystore.AndroidKeyStore
import us.z1x.fidont.store.Database
import us.z1x.fidont.transport.dongle.Dongle
import us.z1x.fidont.transport.hid.HidKey
import us.z1x.fidont.ui.PromptActivity
import java.io.IOException
import java.util.concurrent.Executors

class App : Application() {
    val credentials by lazy { Database(AndroidSqliteDriver(Database.Schema, this, "fidont.db")).credentialQueries }
    val keys by lazy { AndroidKeyStore(this) }
    val authenticator by lazy { Authenticator(keys, credentials) { PromptActivity.choose(this, it) } }
    val dongle by lazy { Dongle(this) }
    val hid by lazy { HidKey(this) }
    val preferences by lazy { Preferences(this) }
    private val writer = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        credentials.all().addListener { saveBackup() }
    }

    fun saveBackup() {
        writer.execute {
            val uri = preferences.backupFile.value ?: return@execute
            val file = keys.automaticBackup(credentials.all().executeAsList()) ?: return@execute
            val saved =
                try {
                    contentResolver.openOutputStream(uri.toUri(), "wt")?.use { it.write(file) } != null
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            preferences.setBackupFailed(!saved)
        }
    }
}

val Context.app get() = applicationContext as App
