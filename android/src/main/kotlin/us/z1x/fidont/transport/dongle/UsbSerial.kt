package us.z1x.fidont.transport.dongle

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

private const val SET_CONTROL_LINE_STATE = 0x22
private const val CLASS_INTERFACE_REQUEST = 0x21
private const val DTR = 1
private const val RTS = 2
private const val TIMEOUT = 3000
private const val BUFFER = 4096

class UsbSerial private constructor(
    private val connection: UsbDeviceConnection,
    private val control: UsbInterface,
    private val data: UsbInterface,
) : Serial {
    private val input = endpoint(UsbConstants.USB_DIR_IN)
    private val output = endpoint(UsbConstants.USB_DIR_OUT)

    override fun write(bytes: ByteArray) {
        if (connection.bulkTransfer(output, bytes, bytes.size, TIMEOUT) != bytes.size) throw IOException()
    }

    override fun read(timeout: Int): ByteArray {
        val buffer = ByteArray(BUFFER)
        val size = connection.bulkTransfer(input, buffer, buffer.size, timeout)
        return buffer.copyOf(maxOf(size, 0))
    }

    override fun lines(
        dtr: Boolean,
        rts: Boolean,
    ) {
        val state = (if (dtr) DTR else 0) or (if (rts) RTS else 0)
        connection.controlTransfer(CLASS_INTERFACE_REQUEST, SET_CONTROL_LINE_STATE, state, control.id, null, 0, TIMEOUT)
    }

    fun close() {
        connection.releaseInterface(control)
        connection.releaseInterface(data)
        connection.close()
    }

    private fun endpoint(direction: Int): UsbEndpoint =
        (0 until data.endpointCount).map(data::getEndpoint).first {
            it.direction == direction && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }

    companion object {
        fun open(
            manager: UsbManager,
            device: UsbDevice,
        ): UsbSerial? {
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val control = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM } ?: return null
            val data = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } ?: return null
            val connection = manager.openDevice(device) ?: return null
            connection.claimInterface(control, true)
            connection.claimInterface(data, true)
            return UsbSerial(connection, control, data)
        }
    }
}
