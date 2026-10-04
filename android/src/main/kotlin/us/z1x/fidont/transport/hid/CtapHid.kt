package us.z1x.fidont.transport.hid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.z1x.fidont.ctap2.Authenticator

const val PACKET_SIZE = 64
private const val HEADER = 7
private const val CONTINUATION_HEADER = 5
private const val MAX_MESSAGE = PACKET_SIZE - HEADER + 128 * (PACKET_SIZE - CONTINUATION_HEADER)
private const val BROADCAST = -1
private const val FIRST = 0x80

private const val PING = 0x01
private const val INIT = 0x06
private const val CBOR = 0x10
private const val CANCEL = 0x11
private const val KEEPALIVE = 0x3b
private const val ERROR = 0x3f

private const val INVALID_COMMAND = 0x01
private const val INVALID_LENGTH = 0x03
private const val INVALID_SEQUENCE = 0x04
private const val BUSY = 0x06
private const val INVALID_CHANNEL = 0x0b

private const val NONCE_SIZE = 8
private const val KEEPALIVE_MS = 100L
private const val USER_PRESENCE_NEEDED = 2
private const val KEEPALIVE_CANCEL = 0x2d

private const val CAPABILITY_CBOR = 0x04
private const val CAPABILITY_NO_MESSAGES = 0x08

// protocol 2 and device version 0.0.0
private val VERSION = byteArrayOf(2, 0, 0, 0, (CAPABILITY_CBOR or CAPABILITY_NO_MESSAGES).toByte())

// the scope must run on one thread
class CtapHid(
    private val scope: CoroutineScope,
    private val authenticator: Authenticator,
    private val send: (ByteArray) -> Unit,
) {
    private var channels = 0
    private var channel = 0
    private var command = 0
    private var size = 0
    private var message = ByteArray(0)
    private var sequence = 0
    private var assembling = false
    private var job: Job? = null

    fun receive(packet: ByteArray) {
        scope.launch { handle(packet) }
    }

    private fun handle(packet: ByteArray) {
        if (packet.size < HEADER) return
        val id = packet.take(4).fold(0) { value, byte -> value shl 8 or (byte.toInt() and 0xff) }
        val type = packet[4].toInt() and 0xff
        if (type and FIRST == 0) return proceed(id, type, packet)
        val length = (packet[5].toInt() and 0xff shl 8) or (packet[6].toInt() and 0xff)
        when {
            type == INIT or FIRST -> {
                if (id == channel) assembling = false
                val assigned = if (id == BROADCAST) ++channels else id
                reply(id, INIT, packet.copyOfRange(HEADER, HEADER + NONCE_SIZE) + bytes(assigned) + VERSION)
            }

            id == 0 || id == BROADCAST -> {
                fail(id, INVALID_CHANNEL)
            }

            job?.isActive == true -> {
                if (id == channel && type == CANCEL or FIRST) job?.cancel() else fail(id, BUSY)
            }

            assembling -> {
                if (id == channel) assembling = false
                fail(id, if (id == channel) INVALID_SEQUENCE else BUSY)
            }

            length > MAX_MESSAGE -> {
                fail(id, INVALID_LENGTH)
            }

            else -> {
                channel = id
                command = type and FIRST.inv()
                size = length
                message = packet.copyOfRange(HEADER, minOf(HEADER + length, packet.size))
                sequence = 0
                assembling = true
                if (message.size == size) dispatch()
            }
        }
    }

    private fun proceed(
        id: Int,
        number: Int,
        packet: ByteArray,
    ) {
        if (!assembling || id != channel) return
        if (number != sequence++) {
            assembling = false
            return fail(id, INVALID_SEQUENCE)
        }
        message += packet.copyOfRange(CONTINUATION_HEADER, minOf(CONTINUATION_HEADER + size - message.size, packet.size))
        if (message.size == size) dispatch()
    }

    private fun dispatch() {
        assembling = false
        val id = channel
        val request = message
        when (command) {
            PING -> {
                reply(id, PING, request)
            }

            CBOR -> {
                job =
                    scope.launch {
                        val alive =
                            launch {
                                while (true) {
                                    delay(KEEPALIVE_MS)
                                    reply(id, KEEPALIVE, byteArrayOf(USER_PRESENCE_NEEDED.toByte()))
                                }
                            }
                        try {
                            val response = authenticator.handle(request)
                            alive.cancel()
                            reply(id, CBOR, response)
                        } catch (e: CancellationException) {
                            reply(id, CBOR, byteArrayOf(KEEPALIVE_CANCEL.toByte()))
                            throw e
                        }
                    }
            }

            CANCEL -> {}

            else -> {
                fail(id, INVALID_COMMAND)
            }
        }
    }

    private fun fail(
        id: Int,
        code: Int,
    ) = reply(id, ERROR, byteArrayOf(code.toByte()))

    private fun reply(
        id: Int,
        type: Int,
        data: ByteArray,
    ) {
        val first = minOf(data.size, PACKET_SIZE - HEADER)
        val head = bytes(id) + byteArrayOf((type or FIRST).toByte(), (data.size shr 8).toByte(), data.size.toByte())
        send((head + data.copyOf(first)).copyOf(PACKET_SIZE))
        data.drop(first).chunked(PACKET_SIZE - CONTINUATION_HEADER).forEachIndexed { index, chunk ->
            send((bytes(id) + index.toByte() + chunk).copyOf(PACKET_SIZE))
        }
    }

    private fun bytes(value: Int) = ByteArray(4) { (value shr 8 * (3 - it)).toByte() }
}
