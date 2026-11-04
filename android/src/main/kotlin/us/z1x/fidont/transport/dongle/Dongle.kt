package us.z1x.fidont.transport.dongle

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import us.z1x.fidont.BuildConfig
import us.z1x.fidont.Light
import us.z1x.fidont.UsbId
import us.z1x.fidont.app
import us.z1x.fidont.dongle.CANCEL
import us.z1x.fidont.dongle.Frames
import us.z1x.fidont.dongle.HELLO
import us.z1x.fidont.dongle.ID_SIZE
import us.z1x.fidont.dongle.INFO
import us.z1x.fidont.dongle.LIGHT
import us.z1x.fidont.dongle.LINK_OFFSET
import us.z1x.fidont.dongle.Link
import us.z1x.fidont.dongle.PAIR
import us.z1x.fidont.dongle.REQUEST
import us.z1x.fidont.dongle.RESPONSE
import us.z1x.fidont.dongle.SECRET_SIZE
import us.z1x.fidont.dongle.STATUS
import us.z1x.fidont.dongle.UNPAIR
import us.z1x.fidont.dongle.UNPAIR_FINISH
import us.z1x.fidont.dongle.UNPAIR_STATUS
import us.z1x.fidont.dongle.UPDATE_BEGIN
import us.z1x.fidont.dongle.UPDATE_DATA
import us.z1x.fidont.dongle.UPDATE_END
import us.z1x.fidont.dongle.USB_ID
import us.z1x.fidont.dongle.frame
import us.z1x.fidont.dongle.linkId
import us.z1x.fidont.dongle.linkPartition
import us.z1x.fidont.dongle.pairedSecret
import us.z1x.fidont.dongle.pairingCode
import us.z1x.fidont.ecdh
import us.z1x.fidont.ecdhGenerate
import us.z1x.fidont.random
import us.z1x.fidont.sha256
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val SERVICE = UUID.fromString("f1d0a000-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val RX = UUID.fromString("f1d0a001-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val TX = UUID.fromString("f1d0a002-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val NOTIFICATIONS = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val FIRMWARE = "firmware.bin"
private const val RELEASE = "https://github.com/Z1xus/fidont/releases/download/v${BuildConfig.VERSION_NAME}/$FIRMWARE"

// the public half of the key that signs the firmware of a release
private const val KEY =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEMWl4HXadTlTux3LzNW81FOdvNT3Ae/Y+pah5KRSIuMhRD+SG1V4U+9MBHxnk1buDaStY3O58m3fWaxBO7J6gaw=="
private const val SECRET = "secret"
private const val COMPUTERS = "computers"
private const val UNPAIRING = "unpairing"
private const val REPAIR = "repair"
private const val ASKED = "asked"
private const val MTU = 517
private const val DEFAULT_MTU = 23
private const val ATT_HEADER = 3

// Android refuses a longer write, also when the MTU allows it
private const val MAX_WRITE = 512
private const val HELLO_SIZE = 16

// offset of the first app partition in firmware/partitions.csv
private const val APP_OFFSET = 0x20000

// where the app image keeps the hash of its build
private const val BUILD_OFFSET = 0xb0
private const val BUILD_SIZE = 32
private const val UPDATE_CHUNK = 2048

// Android throttles an app that starts more than 5 scans in 30 seconds
private val RETRY = 8.seconds
private val GATT_TIMEOUT = 15.seconds
private val CONNECT_TIMEOUT = 60.seconds

private val RELEASE_TIMEOUT = 2.seconds
private val RELEASE_RETRY = 50.milliseconds

sealed interface Status {
    data object Searching : Status

    data object Connecting : Status

    data object Connected : Status

    data class Updating(
        val progress: Float,
    ) : Status
}

// a dongle has no name, a computer that runs the helper has one
class Paired(
    val secret: ByteArray,
    val computer: String?,
) {
    val id = linkId(secret).toHexString()
}

class Dongle(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences("dongle", Context.MODE_PRIVATE)
    private val companions = context.getSystemService(CompanionDeviceManager::class.java)
    private val serving = Mutex()
    private val scanning = Mutex()
    private var scanned = TimeSource.Monotonic.markNow() - RETRY
    private val pairingScan = MutableStateFlow(false)
    val links = MutableStateFlow(load())
    val status = MutableStateFlow(emptyMap<String, Status>())
    val pending = MutableStateFlow(0)
    val unpairing = MutableStateFlow(preferences.getString(UNPAIRING, null))
    val unpairFailed = MutableStateFlow(false)
    val unpairSaveFailed = MutableStateFlow(false)
    val repair = MutableStateFlow(preferences.getBoolean(REPAIR, false))
    private val unpairAttempt = MutableStateFlow(0)

    // the signature makes the source of the files not matter
    suspend fun firmware(): ByteArray? =
        withContext(Dispatchers.IO) {
            val folder = context.getExternalFilesDir(null) ?: context.filesDir
            val files = listOf(File(folder, FIRMWARE), File(folder, "$FIRMWARE.sig"))
            if (files.all(File::exists)) {
                val (image, signature) = files.map(File::readBytes)
                if (signed(image, signature)) return@withContext image
            }
            try {
                val custom = context.app.preferences.firmware.value
                val url = custom.ifEmpty { RELEASE }
                val client = OkHttpClient()
                val (image, signature) =
                    listOf(url, "$url.sig").map {
                        val request = Request.Builder().url(it).build()
                        client.newCall(request).execute().use { response ->
                            val source = response.body.source()
                            source.request(FLASH_SIZE.toLong())
                            source.readByteArray(minOf(source.buffer.size, FLASH_SIZE.toLong()))
                        }
                    }
                if (!signed(image, signature)) return@withContext null
                files[0].writeBytes(image)
                files[1].writeBytes(signature)
                image
            } catch (_: IOException) {
                null
            }
        }

    suspend fun setUp(
        device: UsbDevice,
        progress: (Float?) -> Unit,
    ): Boolean {
        val paired = withContext(Dispatchers.IO) {
            val secret = random(SECRET_SIZE)
            val image = firmware() ?: return@withContext null
            linkPartition(secret).copyInto(image, LINK_OFFSET)
            val serial = UsbSerial.open(context.getSystemService(UsbManager::class.java), device) ?: return@withContext null
            try {
                Flasher(serial).flash(image) { progress(it) }
                Paired(secret, null)
            } catch (_: IOException) {
                null
            } finally {
                serial.close()
            }
        } ?: return false
        status.update { it + (paired.id to Status.Connecting) }
        add(paired)
        progress(null)
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT) {
                combine(status, links) { states, saved -> states[paired.id] != Status.Connecting || saved.none { it.id == paired.id } }
                    .first { it }
            }
        } finally {
            status.update { if (it[paired.id] == Status.Connecting) it - paired.id else it }
        }
        return true
    }

    // a person compares the code with the one on the computer, a dongle has no screen for that
    suspend fun pair(
        computer: Boolean,
        activity: Activity?,
        onLinked: () -> Unit,
        confirm: suspend (String) -> Boolean,
    ): Boolean {
        var paired = false
        val found = try {
            pairingScan.value = true
            find(listOf(ByteArray(ID_SIZE) { if (computer) -1 else 0 }))
        } finally {
            pairingScan.value = false
        }
        val (device, id) = found ?: return false
        associate(id, activity)
        connect(device) { gatt ->
            val key = ecdhGenerate()
            if (!gatt.send { frame(byteArrayOf(PAIR.toByte()) + key.public) }) return@connect
            val dongleKey = withTimeoutOrNull(GATT_TIMEOUT) { gatt.receive() } ?: return@connect
            val secret = pairedSecret(ecdh(key.private, dongleKey) ?: return@connect, key.public, dongleKey)
            val link = Link(secret, key.public, dongleKey)
            val build = greeting(gatt, link) ?: return@connect
            val name = if (computer) build.decodeToString() else null
            if (computer && (build.size == BUILD_SIZE || !confirm(pairingCode(secret)))) return@connect
            session(gatt, link, Paired(secret, name), build) {
                add(it)
                paired = true
                onLinked()
            }
        }
        return paired
    }

    suspend fun serve(): Unit =
        serving.withLock {
            coroutineScope {
                val sessions = this
                val busy = MutableStateFlow(emptySet<String>())
                val active = status.map { states -> states.filterValues { it == Status.Connected || it is Status.Updating }.keys }.distinctUntilChanged()
                combine(busy, active, links) { current, connected, saved ->
                    saved.filter { it.id !in current && it.id !in connected }
                }.collectLatest { idle ->
                    while (idle.isNotEmpty()) {
                        val retry = idle.firstOrNull { it.computer == null && it.id == unpairing.value }
                        val ids = idle.map { linkId(it.secret) } + if (retry != null) listOf(ByteArray(ID_SIZE)) else emptyList()
                        val (device, id) = find(ids, background = true) ?: continue
                        val paired = idle.firstOrNull { linkId(it.secret).contentEquals(id) }
                            ?: retry?.takeIf { id.contentEquals(ByteArray(ID_SIZE)) }
                            ?: continue
                        associate(id)
                        busy.update { it + paired.id }
                        sessions.launch {
                            try {
                                status.update { it + (paired.id to Status.Connecting) }
                                connect(device) { gatt ->
                                    val hello = random(HELLO_SIZE)
                                    if (!gatt.send { frame(byteArrayOf(HELLO.toByte()) + hello) }) return@connect
                                    val response = withTimeoutOrNull(GATT_TIMEOUT) { gatt.receive() } ?: return@connect
                                    val secret = if (id.contentEquals(ByteArray(ID_SIZE)) && unpairing.value == paired.id) {
                                        sha256("fidont unpair recovery".encodeToByteArray() + paired.secret)
                                    } else {
                                        paired.secret
                                    }
                                    val link = Link(secret, hello, response)
                                    session(gatt, link, paired, greeting(gatt, link) ?: return@connect) {}
                                }
                            } finally {
                                status.update { if (it[paired.id] == Status.Connecting) it - paired.id else it }
                                busy.update { it - paired.id }
                            }
                        }
                        break
                    }
                }
            }
        }

    // a computer advertises only while a request waits and a dongle then ends its ID with a 1
    // so Android can tell us when the app is closed
    @SuppressLint("MissingPermission")
    fun watch(enabled: Boolean) {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return
        val intent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RequestReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        try {
            scanner.stopScan(intent)
            if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION)
            val waiting = links.value.map { filter(linkId(it.secret) + if (it.computer == null) byteArrayOf(1) else byteArrayOf()) }
            if (enabled && waiting.isNotEmpty()) scanner.startScan(waiting, ScanSettings.Builder().build(), intent)
        } catch (error: SecurityException) {
            Log.w("fidont", "Background Bluetooth scan unavailable", error)
        }
    }

    fun forget(id: String, completed: Boolean = false): Boolean {
        val dongle = links.value.any { it.id == id && it.computer == null }
        if (completed && (!dongle || unpairing.value != id)) return false
        val remaining = links.value.filter { it.id != id }
        val editor = preferences.edit()
            .putString(SECRET, remaining.firstOrNull { it.computer == null }?.secret?.toHexString())
            .putStringSet(COMPUTERS, remaining.filter { it.computer != null }.map { "${it.secret.toHexString()} ${it.computer}" }.toSet())
        if (unpairing.value == id) editor.remove(UNPAIRING)
        if (completed) editor.putBoolean(REPAIR, true)
        if (!editor.commit()) {
            if (dongle) {
                unpairSaveFailed.value = true
                unpairFailed.value = true
            }
            return false
        }
        links.value = remaining
        if (unpairing.value == id) unpairing.value = null
        if (completed) repair.value = true
        if (dongle) {
            unpairFailed.value = false
            unpairSaveFailed.value = false
        }
        if (links.value.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 33) {
                companions?.myAssociations?.forEach { companions.disassociate(it.id) }
            } else {
                companions?.associations?.forEach { companions.disassociate(it) }
            }
            preferences.edit { remove(ASKED) }
        }
        if (completed) unpairedNotification(context)
        return true
    }

    fun unpair(id: String) {
        if (unpairing.value != id) {
            if (!preferences.edit().putString(UNPAIRING, id).commit()) {
                unpairSaveFailed.value = true
                unpairFailed.value = true
                return
            }
            unpairing.value = id
        }
        unpairFailed.value = false
        unpairSaveFailed.value = false
        unpairAttempt.update { it + 1 }
    }

    // Android lets an app with an associated device show the prompt while the app is closed
    private suspend fun associate(id: ByteArray, activity: Activity? = null) {
        val requestContext = if (Build.VERSION.SDK_INT < 33) activity ?: return else context
        val companions = requestContext.getSystemService(CompanionDeviceManager::class.java)
        if (companions == null || preferences.getBoolean(ASKED, false)) return
        val associated = if (Build.VERSION.SDK_INT >= 33) companions.myAssociations.isNotEmpty() else companions.associations.isNotEmpty()
        if (associated) return
        preferences.edit { putBoolean(ASKED, true) }
        val device = BluetoothLeDeviceFilter.Builder().setScanFilter(filter(id)).build()
        val request =
            AssociationRequest
                .Builder()
                .addDeviceFilter(device)
                .setSingleDevice(true)
                .build()
        val foreground = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        } else {
            null
        }
        suspendCancellableCoroutine { continuation ->
            val callback =
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(sender: IntentSender) =
                        requestContext.startIntentSender(sender, null, 0, 0, 0, foreground)

                    override fun onDeviceFound(sender: IntentSender) {
                        requestContext.startIntentSender(sender, null, 0, 0, 0, foreground)
                        continuation.resume(Unit)
                    }

                    override fun onAssociationCreated(association: AssociationInfo) = continuation.resume(Unit)

                    override fun onFailure(error: CharSequence?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            if (Build.VERSION.SDK_INT >= 33) {
                companions.associate(request, context.mainExecutor, callback)
            } else {
                companions.associate(request, callback, null)
            }
        }
    }

    // the phone keeps one dongle and any number of computers
    private fun add(paired: Paired) {
        if (paired.computer == null && unpairing.value != null && unpairing.value != paired.id) {
            preferences.edit(commit = true) { remove(UNPAIRING) }
            unpairing.value = null
        }
        if (paired.computer == null) {
            unpairFailed.value = false
            unpairSaveFailed.value = false
            preferences.edit { remove(REPAIR) }
            repair.value = false
            context.getSystemService(NotificationManager::class.java).cancel(UNPAIRED_NOTIFICATION)
        }
        save(links.value.filter { it.computer != null || paired.computer != null } + paired)
    }

    private fun load(): List<Paired> {
        val dongle = preferences.getString(SECRET, null)?.let { Paired(it.hexToByteArray(), null) }
        val computers =
            preferences.getStringSet(COMPUTERS, emptySet())!!.map {
                Paired(it.substringBefore(' ').hexToByteArray(), it.substringAfter(' '))
            }
        return listOfNotNull(dongle) + computers.sortedBy { it.computer }
    }

    private fun save(value: List<Paired>) {
        preferences.edit {
            putString(SECRET, value.firstOrNull { it.computer == null }?.secret?.toHexString())
            putStringSet(COMPUTERS, value.filter { it.computer != null }.map { "${it.secret.toHexString()} ${it.computer}" }.toSet())
        }
        links.value = value
    }

    // the version is part of what is signed, so the firmware of another release fails
    private fun signed(
        image: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(KEY, Base64.DEFAULT)))
        val check = Signature.getInstance("SHA256withECDSA")
        check.initVerify(key)
        check.update(image)
        check.update(BuildConfig.VERSION_NAME.toByteArray())
        return try {
            check.verify(signature)
        } catch (_: SignatureException) {
            false
        }
    }

    private fun filter(id: ByteArray) = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), id).build()

    @SuppressLint("MissingPermission")
    private suspend fun connect(
        device: BluetoothDevice,
        block: suspend (Gatt) -> Unit,
    ) {
        val gatt = Gatt(context, device)
        try {
            if (gatt.open()) block(gatt)
        } finally {
            gatt.close()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun find(ids: List<ByteArray>, background: Boolean = false): Pair<BluetoothDevice, ByteArray>? {
        if (background) pairingScan.first { !it }
        scanning.withLock {
            if (background && pairingScan.value) return null
            delay(RETRY - scanned.elapsedNow())
            if (background && pairingScan.value) return null
            scanned = TimeSource.Monotonic.markNow()
        }
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return null
        return suspendCancellableCoroutine { continuation ->
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val id = result.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
                        scanner.stopScan(this)
                        if (continuation.isActive) continuation.resume(result.device to id.copyOf(ID_SIZE))
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(ids.map(::filter), settings, callback)
            continuation.invokeOnCancellation { scanner.stopScan(callback) }
        }
    }

    // a dongle answers with the hash of its build, a computer with its name
    private suspend fun greeting(
        gatt: Gatt,
        link: Link,
    ): ByteArray? =
        withTimeoutOrNull(GATT_TIMEOUT) { gatt.receive() }
            ?.let(link::decrypt)
            ?.takeIf { it[0].toInt() == STATUS }
            ?.let { it.copyOfRange(1, it.size) }

    private suspend fun session(
        gatt: Gatt,
        link: Link,
        paired: Paired,
        build: ByteArray,
        linked: (Paired) -> Unit,
    ) = coroutineScope {
        // a pairing dongle keeps the secret once it gets this, so the phone keeps it after
        if (!gatt.send { frame(link.encrypt(INFO, context.app.authenticator.info)) }) return@coroutineScope
        status.update { it + (paired.id to Status.Connected) }
        try {
            linked(paired)
            launch {
                links.first { current -> current.none { it.id == paired.id } }
                gatt.close()
            }
            val replies = Channel<ByteArray>(Channel.UNLIMITED)
            val updating = if (paired.computer == null && unpairing.value != paired.id) {
                launch(Dispatchers.IO) { update(gatt, link, paired.id, build) }
            } else {
                null
            }
            val settings = if (paired.computer == null && unpairing.value != paired.id) {
                launch {
                    launch {
                        context.app.preferences.usbId
                            .collect { gatt.send { frame(link.encrypt(USB_ID, it.bytes())) } }
                    }
                    context.app.preferences.light
                        .collect { gatt.send { frame(link.encrypt(LIGHT, it.bytes())) } }
                }
            } else {
                null
            }
            if (paired.computer == null) {
                launch {
                    combine(unpairing, unpairAttempt) { id, attempt -> id to attempt }.collectLatest { (id, _) ->
                        if (id != paired.id) return@collectLatest
                        updating?.join()
                        settings?.cancelAndJoin()
                        pending.first { it == 0 }
                        val token = random(8)
                        val done = withTimeoutOrNull(GATT_TIMEOUT * 2) {
                            unpairOperation(gatt, link, UNPAIR, token, replies) &&
                                unpairOperation(gatt, link, UNPAIR_FINISH, token, replies)
                        } == true
                        if (done) forget(paired.id, completed = true) else unpairFailed.value = true
                    }
                }
            }
            var request: Job? = null
            while (true) {
                val message = gatt.receive()?.let(link::decrypt) ?: break
                when (message[0].toInt()) {
                    REQUEST -> {
                        request =
                            launch(Dispatchers.Default) {
                                pending.update { it + 1 }
                                try {
                                    val response = context.app.authenticator.handle(message.copyOfRange(1, message.size))
                                    gatt.send { frame(link.encrypt(RESPONSE, response)) }
                                } finally {
                                    pending.update { it - 1 }
                                }
                            }
                    }

                    CANCEL -> {
                        request?.cancel()
                    }

                    UNPAIR_STATUS -> {
                        replies.trySend(message.copyOfRange(1, message.size))
                    }
                }
            }
            coroutineContext.cancelChildren()
        } finally {
            status.update { it - paired.id }
        }
    }

    private suspend fun unpairOperation(
        gatt: Gatt,
        link: Link,
        type: Int,
        token: ByteArray,
        replies: Channel<ByteArray>,
    ): Boolean {
        if (!gatt.send { frame(link.encrypt(type, token)) }) return false
        while (true) {
            val reply = replies.receive()
            if (reply.size == 10 && reply[0].toInt() == type && reply.copyOfRange(1, 9).contentEquals(token)) {
                return reply[9].toInt() == 0
            }
        }
    }

    private suspend fun update(
        gatt: Gatt,
        link: Link,
        id: String,
        build: ByteArray,
    ) {
        val image = firmware() ?: return
        if (image.copyOfRange(APP_OFFSET + BUILD_OFFSET, APP_OFFSET + BUILD_OFFSET + BUILD_SIZE).contentEquals(build)) return
        val size = image.size - APP_OFFSET
        gatt.send { frame(link.encrypt(UPDATE_BEGIN, ByteArray(0))) }
        for (offset in APP_OFFSET until image.size step UPDATE_CHUNK) {
            val chunk = image.copyOfRange(offset, min(offset + UPDATE_CHUNK, image.size))
            if (!gatt.send { frame(link.encrypt(UPDATE_DATA, chunk)) }) return
            status.update { it + (id to Status.Updating((offset - APP_OFFSET).toFloat() / size)) }
        }
        gatt.send { frame(link.encrypt(UPDATE_END, ByteArray(0))) }
    }
}

@SuppressLint("MissingPermission")
private class Gatt(
    context: Context,
    device: BluetoothDevice,
) : BluetoothGattCallback() {
    private val connected = Channel<Int>(Channel.CONFLATED)
    private val mtuChanged = Channel<Int>(Channel.CONFLATED)
    private val discovered = Channel<Int>(Channel.CONFLATED)
    private val descriptorWritten = Channel<Int>(Channel.CONFLATED)
    private val characteristicWritten = Channel<Int>(Channel.CONFLATED)
    private val fragments = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = Frames()
    private val writing = Mutex()
    private val closing = Mutex()
    private val released = Channel<Int>(Channel.CONFLATED)
    @Volatile
    private var closed = false
    private var gattClosed = false

    // the replacement needs Android 17
    @Suppress("DEPRECATION")
    private val gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile
    private var releasing = false
    @Volatile
    private var payload = DEFAULT_MTU - ATT_HEADER

    suspend fun open(): Boolean {
        if (!done(connected)) return false
        if (Build.VERSION.SDK_INT >= 33 && (!gatt.requestMtu(MTU) || !done(mtuChanged))) return false
        if (!gatt.discoverServices() || !done(discovered)) return false
        val service = gatt.getService(SERVICE) ?: return false
        val tx = service.getCharacteristic(TX)?.also { this.tx = it } ?: return false
        rx = service.getCharacteristic(RX) ?: return false
        if (!gatt.setCharacteristicNotification(tx, true)) return false
        val descriptor = tx.getDescriptor(NOTIFICATIONS) ?: return false
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        if (!started) return false
        return done(descriptorWritten)
    }

    suspend fun send(frame: () -> ByteArray): Boolean =
        writing.withLock {
            if (closed) return false
            try {
                val bytes = frame()
                val chunkSize = payload
                for (offset in bytes.indices step chunkSize) {
                    val chunk = bytes.copyOfRange(offset, min(offset + chunkSize, bytes.size))
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(rx!!, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    } else {
                        rx!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        rx!!.value = chunk
                        gatt.writeCharacteristic(rx!!)
                    }
                    if (!started || !done(characteristicWritten)) {
                        close()
                        return false
                    }
                }
                true
            } catch (error: CancellationException) {
                close()
                throw error
            }
        }

    suspend fun receive(): ByteArray? {
        while (true) {
            frames.add(fragments.receiveCatching().getOrNull() ?: return null)?.let { return it }
        }
    }

    // another app can hold the link open, so the dongle drops it when notifications stop
    suspend fun close() {
        withContext(NonCancellable) {
            closing.withLock {
                if (gattClosed) return@withLock
                closed = true
                connected.close()
                mtuChanged.close()
                discovered.close()
                descriptorWritten.close()
                characteristicWritten.close()
                fragments.close()
                withTimeoutOrNull(RELEASE_TIMEOUT) {
                    val descriptor = tx?.getDescriptor(NOTIFICATIONS) ?: return@withTimeoutOrNull
                    releasing = true
                    // a write cancelled with the session can still be in flight
                    while (!disable(descriptor)) {
                        if (released.tryReceive().isClosed) return@withTimeoutOrNull
                        delay(RELEASE_RETRY)
                    }
                    released.receiveCatching()
                }
                gatt.close()
                gattClosed = true
                released.close()
            }
        }
    }

    private fun disable(descriptor: BluetoothGattDescriptor) =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

    private suspend fun done(channel: Channel<Int>) =
        withTimeoutOrNull(GATT_TIMEOUT) { channel.receiveCatching().getOrNull() == BluetoothGatt.GATT_SUCCESS } == true

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected.trySend(status)
        } else {
            released.close()
            closed = true
            connected.close()
            mtuChanged.close()
            discovered.close()
            descriptorWritten.close()
            characteristicWritten.close()
            fragments.close()
        }
    }

    override fun onMtuChanged(
        gatt: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) payload = min(mtu - ATT_HEADER, MAX_WRITE)
        if (Build.VERSION.SDK_INT >= 33) mtuChanged.trySend(status)
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int,
    ) {
        discovered.trySend(status)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid == NOTIFICATIONS) (if (releasing) released else descriptorWritten).trySend(status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (characteristic.uuid == RX) characteristicWritten.trySend(status)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        fragments.trySend(value)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT < 33) fragments.trySend(characteristic.value)
    }
}

// the eye reads the square of the slider as an even change
private fun Light.bytes(): ByteArray {
    val color = Color.HSVToColor(floatArrayOf(hue, 1f, brightness * brightness))
    return byteArrayOf(mode.ordinal.toByte(), Color.red(color).toByte(), Color.green(color).toByte(), Color.blue(color).toByte())
}

private fun UsbId.bytes() = byteArrayOf((vendor shr 8).toByte(), vendor.toByte(), (product shr 8).toByte(), product.toByte())
