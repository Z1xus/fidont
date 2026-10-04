package us.z1x.fidont

import android.app.Application
import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import us.z1x.fidont.ctap2.Authenticator
import us.z1x.fidont.keystore.AndroidKeyStore
import us.z1x.fidont.store.Database
import us.z1x.fidont.transport.dongle.Dongle
import us.z1x.fidont.ui.PromptActivity

class App : Application() {
    val credentials by lazy { Database(AndroidSqliteDriver(Database.Schema, this, "fidont.db")).credentialQueries }
    val keys by lazy { AndroidKeyStore(this) }
    val authenticator by lazy { Authenticator(keys, credentials) { PromptActivity.choose(this, it) } }
    val dongle by lazy { Dongle(this) }
    val preferences by lazy { Preferences(this) }
}

val Context.app get() = applicationContext as App
