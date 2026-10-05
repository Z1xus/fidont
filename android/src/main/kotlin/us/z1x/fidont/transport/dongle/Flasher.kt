package us.z1x.fidont.transport.dongle

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min

private const val FLASH_BEGIN = 0x02
private const val FLASH_DATA = 0x03
private const val SYNC = 0x08
private const val WRITE_REGISTER = 0x09
private const val READ_REGISTER = 0x0a
private const val SPI_SET_PARAMS = 0x0b
private const val SPI_ATTACH = 0x0d
private const val SPI_FLASH_MD5 = 0x13

private const val END = 0xc0
private const val ESCAPE = 0xdb
private const val ESCAPED_END = 0xdc
private const val ESCAPED_ESCAPE = 0xdd

private const val CHIP_MAGIC = 0x40001000L
private const val ESP32_S3 = 9L
private const val WATCHDOG_PROTECT = 0x600080b0L
private const val WATCHDOG_KEY = 0x50d83aa1L
private const val WATCHDOG_CONFIG = 0x60008098L
private const val SUPER_WATCHDOG_PROTECT = 0x600080b8L
private const val SUPER_WATCHDOG_KEY = 0x8f1d312aL
private const val SUPER_WATCHDOG_CONFIG = 0x600080b4L
private const val SUPER_WATCHDOG_AUTO_FEED = 1L shl 31
private const val WATCHDOG_TIMEOUT = 0x6000809cL
private const val RESET_DELAY = 2000L

// enabled, a system reset at the first stage, one slow clock cycle of reset signal
private const val RESET_SYSTEM = (1L shl 31) or (5L shl 28) or (1L shl 8) or 2L

// the lines set this to enter the bootloader, and it would do so again after the reset
private const val OPTION = 0x6000812cL
private const val FORCE_DOWNLOAD_BOOT = 1L

private const val BLOCK = 0x400
private const val FLASH_SIZE = 4 * 1024 * 1024
private const val CHECKSUM_SEED = 0xef
private const val MD5_SIZE = 32
private const val TIMEOUT = 3000
private const val SYNC_TIMEOUT = 100
private const val SYNC_ATTEMPTS = 20

// the ROM erases before it answers, about 40 s for each megabyte at worst
private const val ERASE_TIMEOUT = 120_000
private const val LINE_DELAY = 100L

interface Serial {
    fun write(bytes: ByteArray)

    fun read(timeout: Int): ByteArray

    fun lines(
        dtr: Boolean,
        rts: Boolean,
    )
}

class Flasher(
    private val serial: Serial,
) {
    private val input = ArrayDeque<Byte>()

    fun flash(
        image: ByteArray,
        progress: (Float) -> Unit,
    ) {
        bootloader()
        sync()
        if (command(READ_REGISTER, words(CHIP_MAGIC)).first != ESP32_S3) throw IOException()
        disableWatchdogs()
        command(SPI_ATTACH, words(0, 0))
        command(SPI_SET_PARAMS, words(0, FLASH_SIZE.toLong(), 0x10000, 0x1000, 0x100, 0xffff))

        val blocks = (image.size + BLOCK - 1) / BLOCK
        command(FLASH_BEGIN, words(image.size.toLong(), blocks.toLong(), BLOCK.toLong(), 0, 0), timeout = ERASE_TIMEOUT)
        for (index in 0 until blocks) {
            val block = ByteArray(BLOCK) { 0xff.toByte() }
            image.copyInto(block, 0, index * BLOCK, min(image.size, (index + 1) * BLOCK))
            val checksum = block.fold(CHECKSUM_SEED) { sum, byte -> sum xor byte.toUByte().toInt() }
            command(FLASH_DATA, words(BLOCK.toLong(), index.toLong(), 0, 0) + block, checksum)
            progress((index + 1f) / blocks)
        }

        val expected = MessageDigest.getInstance("MD5").digest(image).toHexString()
        val actual = command(SPI_FLASH_MD5, words(0, image.size.toLong(), 0, 0), result = MD5_SIZE).second
        if (actual.decodeToString() != expected) throw IOException()
        reset()
    }

    // the chip enters the bootloader only if the lines pass through both set
    private fun bootloader() {
        serial.lines(dtr = false, rts = false)
        Thread.sleep(LINE_DELAY)
        serial.lines(dtr = true, rts = false)
        Thread.sleep(LINE_DELAY)
        serial.lines(dtr = true, rts = true)
        serial.lines(dtr = false, rts = true)
        Thread.sleep(LINE_DELAY)
        serial.lines(dtr = false, rts = false)
    }

    // a reset over the lines keeps the BOOT button state from power on, the watchdog reads it again
    private fun reset() {
        command(WRITE_REGISTER, words(OPTION, 0, FORCE_DOWNLOAD_BOOT, 0))
        command(WRITE_REGISTER, words(WATCHDOG_PROTECT, WATCHDOG_KEY, 0xffffffff, 0))
        command(WRITE_REGISTER, words(WATCHDOG_TIMEOUT, RESET_DELAY, 0xffffffff, 0))
        command(WRITE_REGISTER, words(WATCHDOG_CONFIG, RESET_SYSTEM, 0xffffffff, 0))
        command(WRITE_REGISTER, words(WATCHDOG_PROTECT, 0, 0xffffffff, 0))
    }

    private fun sync() {
        val pattern = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(SYNC_ATTEMPTS) {
            try {
                command(SYNC, pattern, timeout = SYNC_TIMEOUT)
                // the ROM answers a sync several times
                while (serial.read(SYNC_TIMEOUT).isNotEmpty()) continue
                input.clear()
                return
            } catch (_: IOException) {
            }
        }
        throw IOException()
    }

    // over USB nothing feeds the watchdogs, and they would reset the chip during a flash
    private fun disableWatchdogs() {
        command(WRITE_REGISTER, words(WATCHDOG_PROTECT, WATCHDOG_KEY, 0xffffffff, 0))
        command(WRITE_REGISTER, words(WATCHDOG_CONFIG, 0, 0xffffffff, 0))
        command(WRITE_REGISTER, words(WATCHDOG_PROTECT, 0, 0xffffffff, 0))
        val config = command(READ_REGISTER, words(SUPER_WATCHDOG_CONFIG)).first
        command(WRITE_REGISTER, words(SUPER_WATCHDOG_PROTECT, SUPER_WATCHDOG_KEY, 0xffffffff, 0))
        command(WRITE_REGISTER, words(SUPER_WATCHDOG_CONFIG, config or SUPER_WATCHDOG_AUTO_FEED, 0xffffffff, 0))
        command(WRITE_REGISTER, words(SUPER_WATCHDOG_PROTECT, 0, 0xffffffff, 0))
    }

    private fun command(
        operation: Int,
        data: ByteArray,
        checksum: Int = 0,
        result: Int = 0,
        timeout: Int = TIMEOUT,
    ): Pair<Long, ByteArray> {
        val header = byteArrayOf(0, operation.toByte(), data.size.toByte(), (data.size shr 8).toByte())
        write(header + words(checksum.toLong()) + data)
        val deadline = System.nanoTime() + timeout * 1_000_000L
        while (true) {
            val packet = read(deadline)
            if (packet.size < 8 || packet[0].toInt() != 1 || packet[1].toInt() != operation) continue
            val status = packet.getOrNull(8 + result) ?: throw IOException()
            if (status.toInt() != 0) throw IOException()
            val value = (7 downTo 4).fold(0L) { number, index -> number shl 8 or packet[index].toUByte().toLong() }
            return value to packet.copyOfRange(8, 8 + result)
        }
    }

    private fun write(packet: ByteArray) {
        val frame = ByteArrayOutputStream()
        frame.write(END)
        for (byte in packet) {
            when (byte.toUByte().toInt()) {
                END -> frame.write(byteArrayOf(ESCAPE.toByte(), ESCAPED_END.toByte()))
                ESCAPE -> frame.write(byteArrayOf(ESCAPE.toByte(), ESCAPED_ESCAPE.toByte()))
                else -> frame.write(byte.toInt())
            }
        }
        frame.write(END)
        serial.write(frame.toByteArray())
    }

    private fun read(deadline: Long): ByteArray {
        val frame = ByteArrayOutputStream()
        var started = false
        var escaped = false
        while (true) {
            if (input.isEmpty()) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) throw IOException()
                input.addAll(serial.read(remaining.toInt()).asList())
                continue
            }
            val byte = input.removeFirst().toUByte().toInt()
            when {
                byte == END && frame.size() > 0 -> {
                    return frame.toByteArray()
                }

                byte == END -> {
                    started = true
                }

                !started -> {}

                escaped -> {
                    frame.write(if (byte == ESCAPED_END) END else ESCAPE)
                    escaped = false
                }

                byte == ESCAPE -> {
                    escaped = true
                }

                else -> {
                    frame.write(byte)
                }
            }
        }
    }

    private fun words(vararg values: Long): ByteArray = ByteArray(values.size * 4) { (values[it / 4] shr 8 * (it % 4)).toByte() }
}
