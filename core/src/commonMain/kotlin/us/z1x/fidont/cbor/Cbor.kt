package us.z1x.fidont.cbor

object Cbor {
    fun encode(value: Any?): ByteArray =
        when (value) {
            null -> {
                byteArrayOf(0xf6.toByte())
            }

            is Boolean -> {
                byteArrayOf((if (value) 0xf5 else 0xf4).toByte())
            }

            is Int -> {
                encode(value.toLong())
            }

            is Long -> {
                if (value >= 0) head(0, value) else head(1, -1 - value)
            }

            is ByteArray -> {
                head(2, value.size.toLong()) + value
            }

            is String -> {
                value.encodeToByteArray().let { head(3, it.size.toLong()) + it }
            }

            is List<*> -> {
                value.fold(head(4, value.size.toLong())) { bytes, item -> bytes + encode(item) }
            }

            is Map<*, *> -> {
                value
                    .map { (key, item) -> encode(key) to encode(item) }
                    .sortedWith { a, b -> canonical(a.first, b.first) }
                    .fold(head(5, value.size.toLong())) { bytes, (key, item) -> bytes + key + item }
            }

            else -> {
                throw IllegalArgumentException()
            }
        }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
    ): Any? = Reader(bytes, offset).read()

    private fun head(
        major: Int,
        value: Long,
    ): ByteArray {
        val type = major shl 5
        return when {
            value < 24 -> byteArrayOf((type or value.toInt()).toByte())
            value < 0x100 -> byteArrayOf((type or 24).toByte()) + bigEndian(value, 1)
            value < 0x10000 -> byteArrayOf((type or 25).toByte()) + bigEndian(value, 2)
            value < 0x100000000 -> byteArrayOf((type or 26).toByte()) + bigEndian(value, 4)
            else -> byteArrayOf((type or 27).toByte()) + bigEndian(value, 8)
        }
    }

    private fun bigEndian(
        value: Long,
        size: Int,
    ) = ByteArray(size) { (value shr 8 * (size - 1 - it)).toByte() }

    private fun canonical(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        if (a.size != b.size) return a.size - b.size
        val index = a.indices.firstOrNull { a[it] != b[it] } ?: return 0
        return a[index].toUByte().compareTo(b[index].toUByte())
    }

    private class Reader(
        private val bytes: ByteArray,
        private var position: Int,
    ) {
        fun read(): Any? {
            val initial = take(1)[0].toInt() and 0xff
            val major = initial shr 5
            val info = initial and 31
            if (major == 7) {
                return when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    else -> throw IllegalArgumentException()
                }
            }
            val value =
                when (info) {
                    in 0..23 -> info.toLong()
                    in 24..27 -> take(1 shl (info - 24)).fold(0L) { number, byte -> number shl 8 or (byte.toLong() and 0xff) }
                    else -> throw IllegalArgumentException()
                }
            require(value >= 0)
            return when (major) {
                0 -> value
                1 -> -1 - value
                2 -> take(count(value))
                3 -> take(count(value)).decodeToString()
                4 -> List(count(value)) { read() }
                else -> buildMap { repeat(count(value)) { put(read(), read()) } }
            }
        }

        private fun count(value: Long): Int {
            require(value <= bytes.size - position)
            return value.toInt()
        }

        private fun take(size: Int): ByteArray {
            require(size <= bytes.size - position)
            position += size
            return bytes.copyOfRange(position - size, position)
        }
    }
}
