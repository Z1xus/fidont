package us.z1x.fidont.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.z1x.fidont.R

private class Library(
    val name: String,
    val license: String,
    val url: String,
)

private val LIBRARIES =
    listOf(
        R.string.licenses_app to
            listOf(
                Library("Jetpack Compose, CameraX, Credentials", "Apache-2.0", "https://developer.android.com/jetpack"),
                Library("Kotlin and kotlinx.coroutines", "Apache-2.0", "https://kotlinlang.org"),
                Library("OkHttp", "Apache-2.0", "https://square.github.io/okhttp"),
                Library("SQLDelight", "Apache-2.0", "https://sqldelight.github.io/sqldelight"),
                Library("ZXing", "Apache-2.0", "https://github.com/zxing/zxing"),
                Library("Material icons", "Apache-2.0", "https://fonts.google.com/icons"),
                Library("Trusted browser list", "Google", "https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"),
            ),
        R.string.licenses_firmware to
            listOf(
                Library("ESP-IDF", "Apache-2.0", "https://github.com/espressif/esp-idf"),
                Library("TinyUSB", "MIT", "https://github.com/hathach/tinyusb"),
                Library("Apache NimBLE", "Apache-2.0", "https://github.com/apache/mynewt-nimble"),
                Library("Mbed TLS", "Apache-2.0", "https://github.com/Mbed-TLS/mbedtls"),
            ),
        R.string.licenses_relay to
            listOf(
                Library("coder/websocket", "ISC", "https://github.com/coder/websocket"),
            ),
    )

private val PRIVACY =
    listOf(
        R.string.privacy_phone to R.string.privacy_phone_body,
        R.string.privacy_relay to R.string.privacy_relay_body,
        R.string.privacy_dongle to R.string.privacy_dongle_body,
    )

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val links = LocalUriHandler.current
    Page(R.string.privacy, onBack) {
        PRIVACY.forEach { (title, body) ->
            item { SectionHeader(title) }
            item {
                GroupItem(0, 1) {
                    Text(stringResource(body), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item { SectionHeader(R.string.privacy_contact) }
        item { Entry(0, 1, AUTHOR.removePrefix("https://"), onClick = { links.openUri(AUTHOR) }) }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val links = LocalUriHandler.current
    Page(R.string.open_source, onBack) {
        LIBRARIES.forEach { (title, libraries) ->
            item { SectionHeader(title) }
            libraries.forEachIndexed { index, library ->
                item {
                    Entry(index, libraries.size, library.name, supporting = library.license, onClick = { links.openUri(library.url) })
                }
            }
        }
    }
}
