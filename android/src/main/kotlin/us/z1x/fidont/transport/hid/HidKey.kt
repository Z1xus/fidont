package us.z1x.fidont.transport.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import us.z1x.fidont.app

private const val HOST = "host"

// a FIDO HID device with one 64 byte input report and one 64 byte output report
private val DESCRIPTOR = "06d0f10901a1010920150026ff007508954081020921150026ff00750895409102c0".hexToByteArray()
private val SETTINGS =
    BluetoothHidDeviceAppSdpSettings("fidont", "Security key", "fidont", BluetoothHidDevice.SUBCLASS1_NONE, DESCRIPTOR)

sealed interface HidStatus {
    data object Unavailable : HidStatus

    data object Waiting : HidStatus

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
    val status = MutableStateFlow<HidStatus>(HidStatus.Waiting)

    // Android only keeps the key registered while the app is in front
    suspend fun serve() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            var hid: BluetoothHidDevice? = null
            var host: BluetoothDevice? = null
            val ctap = CtapHid(this, context.app.authenticator) { packet -> host?.let { hid?.sendReport(it, 0, packet) } }
            val callback =
                object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(
                        pluggedDevice: BluetoothDevice?,
                        registered: Boolean,
                    ) {
                        if (!registered) {
                            status.value = HidStatus.Unavailable
                            return
                        }
                        status.value = HidStatus.Waiting
                        // a computer waits for its key to come back
                        preferences.getString(HOST, null)?.let { hid?.connect(adapter.getRemoteDevice(it)) }
                    }

                    override fun onConnectionStateChanged(
                        device: BluetoothDevice,
                        state: Int,
                    ) {
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            host = device
                            preferences.edit { putString(HOST, device.address) }
                            status.value = HidStatus.Connected(device.name ?: device.address)
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) {
                            host = null
                            status.value = HidStatus.Waiting
                        }
                    }

                    override fun onInterruptData(
                        device: BluetoothDevice,
                        reportId: Byte,
                        data: ByteArray,
                    ) = ctap.receive(data)

                    override fun onSetReport(
                        device: BluetoothDevice,
                        type: Byte,
                        id: Byte,
                        data: ByteArray,
                    ) {
                        ctap.receive(data)
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
            if (adapter?.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) != true) {
                status.value = HidStatus.Unavailable
                return@withContext
            }
            try {
                awaitCancellation()
            } finally {
                hid?.unregisterApp()
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                status.value = HidStatus.Waiting
            }
        }
    }
}
