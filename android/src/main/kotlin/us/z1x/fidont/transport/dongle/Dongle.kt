package us.z1x.fidont.transport.dongle

import android.annotation.SuppressLint
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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelUuid
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
import us.z1x.fidont.ecdh
import us.z1x.fidont.ecdhGenerate
import us.z1x.fidont.random
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min

private val SERVICE = UUID.fromString("f1d0a000-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val RX = UUID.fromString("f1d0a001-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val TX = UUID.fromString("f1d0a002-6a0b-4d1e-9f5c-3c1e5d0a7e11")
private val NOTIFICATIONS = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val FIRMWARE = "firmware.bin"
private const val SECRET = "secret"
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

sealed interface Status {
    data object Searching : Status

    data object Connected : Status

    data class Updating(
        val progress: Float,
    ) : Status
}

class Dongle(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences("dongle", Context.MODE_PRIVATE)
    val secret = MutableStateFlow(preferences.getString(SECRET, null)?.hexToByteArray())
    val status = MutableStateFlow<Status>(Status.Searching)

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
                save(secret)
                true
            } catch (_: IOException) {
                false
            } finally {
                serial.close()
            }
        }

    suspend fun pair(): Boolean {
        var paired = false
        connect(ByteArray(ID_SIZE)) { gatt ->
            val key = ecdhGenerate()
            gatt.send { frame(byteArrayOf(PAIR.toByte()) + key.public) }
            val dongleKey = gatt.receive() ?: return@connect
            val secret = pairedSecret(ecdh(key.private, dongleKey) ?: return@connect, key.public, dongleKey)
            session(gatt, Link(secret, key.public, dongleKey)) {
                save(secret)
                paired = true
            }
        }
        return paired
    }

    suspend fun serve() {
        val secret = secret.value ?: return
        connect(linkId(secret)) { gatt ->
            val hello = random(HELLO_SIZE)
            gatt.send { frame(byteArrayOf(HELLO.toByte()) + hello) }
            session(gatt, Link(secret, hello, gatt.receive() ?: return@connect)) {}
        }
    }

    fun forget() = save(null)

    private fun save(value: ByteArray?) {
        preferences.edit { putString(SECRET, value?.toHexString()) }
        secret.value = value
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(
        id: ByteArray,
        block: suspend (Gatt) -> Unit,
    ) {
        val gatt = Gatt(context, find(id) ?: return)
        try {
            if (gatt.open()) block(gatt)
        } finally {
            gatt.close()
            status.value = Status.Searching
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun find(id: ByteArray): BluetoothDevice? {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return null
        return suspendCancellableCoroutine { continuation ->
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        scanner.stopScan(this)
                        if (continuation.isActive) continuation.resume(result.device)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), id).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, callback)
            continuation.invokeOnCancellation { scanner.stopScan(callback) }
        }
    }

    private suspend fun session(
        gatt: Gatt,
        link: Link,
        linked: () -> Unit,
    ) = coroutineScope {
        val build = gatt.receive()?.let(link::decrypt)?.takeIf { it[0].toInt() == STATUS } ?: return@coroutineScope
        // a pairing dongle keeps the secret once it gets this, so the phone keeps it after
        if (!gatt.send { frame(link.encrypt(INFO, context.app.authenticator.info)) }) return@coroutineScope
        linked()
        status.value = Status.Connected
        launch(Dispatchers.IO) { update(gatt, link, build.copyOfRange(1, build.size)) }
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
    }

    private suspend fun update(
        gatt: Gatt,
        link: Link,
        build: ByteArray,
    ) {
        val image = context.assets.open(FIRMWARE).use { it.readBytes() }
        if (image.copyOfRange(APP_OFFSET + BUILD_OFFSET, APP_OFFSET + BUILD_OFFSET + BUILD_SIZE).contentEquals(build)) return
        val size = image.size - APP_OFFSET
        gatt.send { frame(link.encrypt(UPDATE_BEGIN, ByteArray(0))) }
        for (offset in APP_OFFSET until image.size step UPDATE_CHUNK) {
            val chunk = image.copyOfRange(offset, min(offset + UPDATE_CHUNK, image.size))
            if (!gatt.send { frame(link.encrypt(UPDATE_DATA, chunk)) }) return
            status.value = Status.Updating((offset - APP_OFFSET).toFloat() / size)
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
