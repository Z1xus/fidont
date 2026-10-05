package us.z1x.fidont.transport.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.z1x.fidont.app
import kotlin.time.Duration.Companion.seconds

private const val HOST = "host"
private val RETRY = 2.seconds

// a FIDO HID device with one 64 byte input report and one 64 byte output report
private val DESCRIPTOR = "06d0f10901a1010920150026ff007508954081020921150026ff00750895409102c0".hexToByteArray()
private val SETTINGS =
    BluetoothHidDeviceAppSdpSettings("fidont", "Security key", "fidont", BluetoothHidDevice.SUBCLASS1_NONE, DESCRIPTOR)

sealed interface HidStatus {
    data object Unsupported : HidStatus

    data object Off : HidStatus

    data object Starting : HidStatus

    data class Ready(
        val failed: Boolean = false,
    ) : HidStatus

    data class Connecting(
        val name: String,
    ) : HidStatus

    data class Connected(
        val name: String,
    ) : HidStatus
}

// the permission is requested before the key is served
@SuppressLint("MissingPermission")
class HidKey(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences("hid", Context.MODE_PRIVATE)
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    val status = MutableStateFlow<HidStatus>(HidStatus.Starting)

    val name: String get() = adapter.name

    val computers: List<BluetoothDevice>
        get() = adapter.bondedDevices.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }

    fun connect(address: String) {
        val device = adapter.getRemoteDevice(address)
        if (hid?.connect(device) == true) status.value = HidStatus.Connecting(device.label)
    }

    fun disconnect() {
        preferences.edit { remove(HOST) }
        host?.let { hid?.disconnect(it) }
    }

    // Android only keeps the key registered while the app is in front
    suspend fun serve() {
        if (adapter?.isEnabled != true) {
            status.value = HidStatus.Off
            return
        }
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val ctap = CtapHid(this, context.app.authenticator) { packet -> host?.let { hid?.sendReport(it, 0, packet) } }
            val callback =
                object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(
                        pluggedDevice: BluetoothDevice?,
                        registered: Boolean,
                    ) {
                        if (registered) {
                            status.value = HidStatus.Ready()
                            // a computer waits for its key to come back, one that was unpaired must pair again
                            val last = preferences.getString(HOST, null)
                            if (computers.any { it.address == last }) connect(last!!)
                        } else {
                            status.value = HidStatus.Starting
                            val callback = this
                            launch {
                                delay(RETRY)
                                hid?.registerApp(SETTINGS, null, null, Runnable::run, callback)
                            }
                        }
                    }

                    override fun onConnectionStateChanged(
                        device: BluetoothDevice,
                        state: Int,
                    ) {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                host = device
                                preferences.edit { putString(HOST, device.address) }
                                status.value = HidStatus.Connected(device.label)
                            }

                            BluetoothProfile.STATE_CONNECTING -> {
                                status.value = HidStatus.Connecting(device.label)
                            }

                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (device == host) host = null
                                status.value = HidStatus.Ready(failed = status.value is HidStatus.Connecting)
                            }
                        }
                    }

                    override fun onInterruptData(
                        device: BluetoothDevice,
                        reportId: Byte,
                        data: ByteArray,
                    ) = ctap.receive(packet(data))

                    override fun onSetReport(
                        device: BluetoothDevice,
                        type: Byte,
                        id: Byte,
                        data: ByteArray,
                    ) {
                        ctap.receive(packet(data))
                        hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
                    }
                }
            val listener =
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(
                        profile: Int,
                        proxy: BluetoothProfile,
                    ) {
                        hid = proxy as BluetoothHidDevice
                        proxy.registerApp(SETTINGS, null, null, Runnable::run, callback)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        hid = null
                    }
                }
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
                status.value = HidStatus.Unsupported
                return@withContext
            }
            try {
                awaitCancellation()
            } finally {
                hid?.unregisterApp()
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                hid = null
                host = null
                status.value = HidStatus.Starting
            }
        }
    }

    // Linux puts the report id in front of the packet, also when it is zero
    private fun packet(data: ByteArray) = data.copyOfRange(maxOf(0, data.size - PACKET_SIZE), data.size)

    private val BluetoothDevice.label get() = name ?: address
}
