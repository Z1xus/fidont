package us.z1x.fidont.transport.dongle

import android.annotation.SuppressLint
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
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelUuid
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.z1x.fidont.app
import us.z1x.fidont.dongle.CANCEL
import us.z1x.fidont.dongle.Frames
import us.z1x.fidont.dongle.HELLO
import us.z1x.fidont.dongle.ID_SIZE
import us.z1x.fidont.dongle.INFO
import us.z1x.fidont.dongle.LINK_OFFSET
import us.z1x.fidont.dongle.Link
import us.z1x.fidont.dongle.PAIR
import us.z1x.fidont.dongle.REQUEST
import us.z1x.fidont.dongle.RESPONSE
import us.z1x.fidont.dongle.SECRET_SIZE
import us.z1x.fidont.dongle.STATUS
import us.z1x.fidont.dongle.UPDATE_BEGIN
import us.z1x.fidont.dongle.UPDATE_DATA
import us.z1x.fidont.dongle.UPDATE_END
import us.z1x.fidont.dongle.frame
import us.z1x.fidont.dongle.linkId
import us.z1x.fidont.dongle.linkPartition
import us.z1x.fidont.dongle.pairedSecret
import us.z1x.fidont.dongle.pairingCode
import us.z1x.fidont.ecdh
import us.z1x.fidont.ecdhGenerate
import us.z1x.fidont.random
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val SERVICE = UUID.fromString("f1d0a000-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val RX = UUID.fromString("f1d0a001-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val TX = UUID.fromString("f1d0a002-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val NOTIFICATIONS = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val FIRMWARE = "firmware.bin"
private const val SECRET = "secret"
private const val COMPUTERS = "computers"
private const val MTU = 517
private const val DEFAULT_MTU = 23
private const val ATT_HEADER = 3
private const val HELLO_SIZE = 16

// offset of the first app partition in firmware/partitions.csv
private const val APP_OFFSET = 0x20000

// where the app image keeps the hash of its build
private const val BUILD_OFFSET = 0xb0
private const val BUILD_SIZE = 32
private const val UPDATE_CHUNK = 2048

// Android throttles an app that starts more than 5 scans in 30 seconds
private val RETRY = 6.seconds

sealed interface Status {
    data object Searching : Status

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
    val links = MutableStateFlow(load())
    val status = MutableStateFlow(emptyMap<String, Status>())

    suspend fun setUp(
        device: UsbDevice,
        progress: (Float) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val secret = random(SECRET_SIZE)
            val image = context.assets.open(FIRMWARE).use { it.readBytes() }
            linkPartition(secret).copyInto(image, LINK_OFFSET)
            val serial = UsbSerial.open(context.getSystemService(UsbManager::class.java), device) ?: return@withContext false
            try {
                Flasher(serial).flash(image, progress)
                add(Paired(secret, null))
                true
            } catch (_: IOException) {
                false
            } finally {
                serial.close()
            }
        }

    // a person compares the code with the one on the computer, a dongle has no screen for that
    suspend fun pair(
        computer: Boolean,
        confirm: suspend (String) -> Boolean,
    ): Boolean {
        var paired = false
        val (device, _) = find(listOf(ByteArray(ID_SIZE) { if (computer) -1 else 0 })) ?: return false
        connect(device) { gatt ->
            val key = ecdhGenerate()
            gatt.send { frame(byteArrayOf(PAIR.toByte()) + key.public) }
            val dongleKey = gatt.receive() ?: return@connect
            val secret = pairedSecret(ecdh(key.private, dongleKey) ?: return@connect, key.public, dongleKey)
            val link = Link(secret, key.public, dongleKey)
            val build = greeting(gatt, link) ?: return@connect
            val name = if (computer) build.decodeToString() else null
            if (computer && (build.size == BUILD_SIZE || !confirm(pairingCode(secret)))) return@connect
            session(gatt, link, Paired(secret, name), build) {
                add(it)
                paired = true
            }
        }
        return paired
    }

    suspend fun serve(): Nothing =
        coroutineScope {
            val sessions = this
            val busy = MutableStateFlow(emptySet<String>())
            var scanned = TimeSource.Monotonic.markNow() - RETRY
            busy.collectLatest { current ->
                val idle = links.value.filter { it.id !in current }
                while (idle.isNotEmpty()) {
                    delay(RETRY - scanned.elapsedNow())
                    scanned = TimeSource.Monotonic.markNow()
                    val (device, id) = find(idle.map { linkId(it.secret) }) ?: continue
                    val paired = idle.first { linkId(it.secret).contentEquals(id) }
                    busy.update { it + paired.id }
                    sessions.launch {
                        try {
                            connect(device) { gatt ->
                                val hello = random(HELLO_SIZE)
                                gatt.send { frame(byteArrayOf(HELLO.toByte()) + hello) }
                                val link = Link(paired.secret, hello, gatt.receive() ?: return@connect)
                                session(gatt, link, paired, greeting(gatt, link) ?: return@connect) {}
                            }
                        } finally {
                            busy.update { it - paired.id }
                        }
                    }
                }
            }
        }

    // a computer advertises only while a request waits, so Android can tell us when the app is closed
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
        scanner.stopScan(intent)
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION)
        val computers = links.value.filter { it.computer != null }.map { filter(linkId(it.secret)) }
        if (enabled && computers.isNotEmpty()) scanner.startScan(computers, ScanSettings.Builder().build(), intent)
    }

    fun forget(id: String) = save(links.value.filter { it.id != id })

    // the phone keeps one dongle and any number of computers
    private fun add(paired: Paired) = save(links.value.filter { it.computer != null || paired.computer != null } + paired)

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
    private suspend fun find(ids: List<ByteArray>): Pair<BluetoothDevice, ByteArray>? {
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
                        if (continuation.isActive) continuation.resume(result.device to id)
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
        gatt
            .receive()
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
        linked(paired)
        status.update { it + (paired.id to Status.Connected) }
        try {
            if (paired.computer == null) launch(Dispatchers.IO) { update(gatt, link, paired.id, build) }
            var request: Job? = null
            while (true) {
                val message = gatt.receive()?.let(link::decrypt) ?: break
                when (message[0].toInt()) {
                    REQUEST -> {
                        request =
                            launch(Dispatchers.Default) {
                                val response = context.app.authenticator.handle(message.copyOfRange(1, message.size))
                                gatt.send { frame(link.encrypt(RESPONSE, response)) }
                            }
                    }

                    CANCEL -> {
                        request?.cancel()
                    }
                }
            }
            coroutineContext.cancelChildren()
        } finally {
            status.update { it - paired.id }
        }
    }

    private suspend fun update(
        gatt: Gatt,
        link: Link,
        id: String,
        build: ByteArray,
    ) {
        val image = context.assets.open(FIRMWARE).use { it.readBytes() }
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
    private val done = Channel<Unit>(Channel.UNLIMITED)
    private val fragments = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = Frames()
    private val writing = Mutex()

    // the replacement needs Android 17
    @Suppress("DEPRECATION")
    private val gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
    private var rx: BluetoothGattCharacteristic? = null
    private var payload = DEFAULT_MTU - ATT_HEADER

    suspend fun open(): Boolean {
        if (!done()) return false
        if (!gatt.requestMtu(MTU) || !done()) return false
        if (!gatt.discoverServices() || !done()) return false
        val service = gatt.getService(SERVICE) ?: return false
        val tx = service.getCharacteristic(TX) ?: return false
        rx = service.getCharacteristic(RX) ?: return false
        gatt.setCharacteristicNotification(tx, true)
        gatt.writeDescriptor(tx.getDescriptor(NOTIFICATIONS), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        return done()
    }

    suspend fun send(frame: () -> ByteArray): Boolean =
        writing.withLock {
            val bytes = frame()
            for (offset in bytes.indices step payload) {
                val chunk = bytes.copyOfRange(offset, min(offset + payload, bytes.size))
                val started = gatt.writeCharacteristic(rx!!, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (started != BluetoothStatusCodes.SUCCESS || !done()) return false
            }
            true
        }

    suspend fun receive(): ByteArray? {
        while (true) {
            frames.add(fragments.receiveCatching().getOrNull() ?: return null)?.let { return it }
        }
    }

    fun close() = gatt.close()

    private suspend fun done() = done.receiveCatching().isSuccess

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            done.trySend(Unit)
        } else {
            done.close()
            fragments.close()
        }
    }

    override fun onMtuChanged(
        gatt: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) {
        payload = mtu - ATT_HEADER
        done.trySend(Unit)
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int,
    ) {
        done.trySend(Unit)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        done.trySend(Unit)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        done.trySend(Unit)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        fragments.trySend(value)
    }
}
