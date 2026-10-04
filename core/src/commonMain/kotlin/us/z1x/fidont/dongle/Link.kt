package us.z1x.fidont.dongle

import us.z1x.fidont.hkdf
import us.z1x.fidont.open
import us.z1x.fidont.seal
import us.z1x.fidont.sha256

const val HELLO = 1
const val PAIR = 2

const val STATUS = 1
const val REQUEST = 2
const val CANCEL = 3

const val INFO = 1
const val RESPONSE = 2
const val UPDATE_BEGIN = 3
const val UPDATE_DATA = 4
const val UPDATE_END = 5

const val SECRET_SIZE = 32
const val ID_SIZE = 8

// offset of the link partition in firmware/partitions.csv
const val LINK_OFFSET = 0x10000
private val LINK_MAGIC = "fdnt".encodeToByteArray()

fun linkId(secret: ByteArray): ByteArray = sha256(secret).copyOf(ID_SIZE)

fun linkPartition(secret: ByteArray): ByteArray = LINK_MAGIC + secret

fun pairedSecret(
    shared: ByteArray,
    phoneKey: ByteArray,
    dongleKey: ByteArray,
): ByteArray = hkdf(shared, phoneKey + dongleKey, "fidont pair".encodeToByteArray(), SECRET_SIZE)

class Link(
    secret: ByteArray,
    phoneHello: ByteArray,
    dongleHello: ByteArray,
) {
    private val keys = hkdf(secret, phoneHello + dongleHello, "fidont link".encodeToByteArray(), 64)
    private var sent = 0
    private var received = 0

    fun encrypt(
        type: Int,
        payload: ByteArray,
    ): ByteArray = seal(keys.copyOf(32), nonce(sent++), byteArrayOf(type.toByte()) + payload, ByteArray(0))

    fun decrypt(message: ByteArray): ByteArray? = open(keys.copyOfRange(32, 64), nonce(received++), message, ByteArray(0))

    private fun nonce(count: Int) = ByteArray(8) + ByteArray(4) { (count shr 8 * (3 - it)).toByte() }
}

fun frame(body: ByteArray): ByteArray = byteArrayOf(body.size.toByte(), (body.size shr 8).toByte()) + body

class Frames {
    private var buffer = ByteArray(0)

    fun add(fragment: ByteArray): ByteArray? {
        buffer += fragment
        if (buffer.size < 2) return null
        val size = buffer[0].toUByte().toInt() or (buffer[1].toUByte().toInt() shl 8)
        if (buffer.size < 2 + size) return null
        return buffer.copyOfRange(2, 2 + size).also { buffer = buffer.copyOfRange(2 + size, buffer.size) }
    }
}
