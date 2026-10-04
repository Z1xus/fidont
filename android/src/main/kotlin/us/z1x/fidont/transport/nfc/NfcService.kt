package us.z1x.fidont.transport.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import us.z1x.fidont.app
import kotlin.math.min

private const val SELECT = 0xa4
private const val MESSAGE = 0x10
private const val GET_RESPONSE = 0xc0
private const val CHAINING = 0x10
private const val SHORT_RESPONSE = 256

private val AID = "a0000006472f0001".hexToByteArray()
private val VERSION = "FIDO_2_0".toByteArray()
private val SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
private val NOT_FOUND = byteArrayOf(0x6a, 0x82.toByte())
private val UNSUPPORTED = byteArrayOf(0x6d, 0x00)

class NfcService : HostApduService() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var request = ByteArray(0)
    private var response = ByteArray(0)

    override fun processCommandApdu(
        apdu: ByteArray,
        extras: Bundle?,
    ): ByteArray? {
        if (apdu.size < 4) return UNSUPPORTED
        val extended = apdu.size >= 7 && apdu[4].toInt() == 0
        val data =
            when {
                apdu.size <= 5 -> ByteArray(0)
                extended -> apdu.copyOfRange(7, min(apdu.size, 7 + (apdu[5].toUByte().toInt() shl 8 or apdu[6].toUByte().toInt())))
                else -> apdu.copyOfRange(5, min(apdu.size, 5 + apdu[4].toUByte().toInt()))
            }
        return when (apdu[1].toUByte().toInt()) {
            SELECT -> {
                if (data.contentEquals(AID)) VERSION + SUCCESS else NOT_FOUND
            }

            MESSAGE -> {
                request += data
                if (apdu[0].toInt() and CHAINING != 0) return SUCCESS
                val message = request
                request = ByteArray(0)
                scope.launch {
                    val reply = app.authenticator.handle(message)
                    if (extended) {
                        sendResponseApdu(reply + SUCCESS)
                    } else {
                        response = reply
                        sendResponseApdu(next())
                    }
                }
                null
            }

            GET_RESPONSE -> {
                next()
            }

            else -> {
                UNSUPPORTED
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        scope.coroutineContext.cancelChildren()
        request = ByteArray(0)
        response = ByteArray(0)
    }

    private fun next(): ByteArray {
        val chunk = response.copyOf(min(response.size, SHORT_RESPONSE))
        response = response.copyOfRange(chunk.size, response.size)
        // 61xx tells the reader how much is left, 0 stands for 256 or more
        val status = if (response.isEmpty()) SUCCESS else byteArrayOf(0x61, min(response.size, SHORT_RESPONSE).toByte())
        return chunk + status
    }
}
