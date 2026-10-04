package us.z1x.fidont.hybrid

import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.p256Decompress

private const val PREFIX = "FIDO:/"
private const val CHUNK_DIGITS = 17
private const val CHUNK_BYTES = 7
private val TAIL_BYTES = mapOf(3 to 1, 5 to 2, 8 to 3, 10 to 4, 13 to 5, 15 to 6)

class Qr(
    val peerIdentity: ByteArray,
    val secret: ByteArray,
) {
    companion object {
        fun parse(text: String): Qr? {
            if (!text.startsWith(PREFIX, ignoreCase = true)) return null
            val bytes = bytes(text.substring(PREFIX.length)) ?: return null
            val contents =
                try {
                    Cbor.decode(bytes) as? Map<*, *>
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            val secret = contents[1L] as? ByteArray ?: return null
            if (secret.size != 16) return null
            val peerIdentity = (contents[0L] as? ByteArray)?.let(::p256Decompress) ?: return null
            return Qr(peerIdentity, secret)
        }

        private fun bytes(digits: String): ByteArray? {
            val bytes = mutableListOf<Byte>()
            for (chunk in digits.chunked(CHUNK_DIGITS)) {
                val size = if (chunk.length == CHUNK_DIGITS) CHUNK_BYTES else TAIL_BYTES[chunk.length] ?: return null
                val value = chunk.toULongOrNull() ?: return null
                repeat(size) { bytes += (value shr 8 * it).toByte() }
            }
            return bytes.toByteArray()
        }
    }
}
