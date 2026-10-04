package us.z1x.fidont.hybrid

import us.z1x.fidont.ecdh
import us.z1x.fidont.ecdhGenerate
import us.z1x.fidont.hkdf
import us.z1x.fidont.open
import us.z1x.fidont.seal
import us.z1x.fidont.sha256

private const val POINT_SIZE = 65
private const val PADDING = 32

class Crypter(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    private var readCount = 0
    private var writeCount = 0

    fun encrypt(message: ByteArray): ByteArray {
        val padded = message.copyOf((message.size + PADDING) / PADDING * PADDING)
        padded[padded.lastIndex] = (padded.size - message.size - 1).toByte()
        return seal(writeKey, nonce(writeCount++), padded, ByteArray(0))
    }

    fun decrypt(message: ByteArray): ByteArray? {
        val padded = open(readKey, nonce(readCount), message, ByteArray(0)) ?: return null
        readCount++
        val size = padded.size - 1 - (padded.lastOrNull() ?: return null).toUByte().toInt()
        return if (size < 0) null else padded.copyOf(size)
    }

    private fun nonce(count: Int) = ByteArray(8) + bigEndian(count)
}

internal class Noise {
    private var chainingKey = "Noise_KNpsk0_P256_AESGCM_SHA256".encodeToByteArray().copyOf(32)
    private var hash = chainingKey
    private var key = ByteArray(0)
    private var count = 0

    fun mixHash(data: ByteArray) {
        hash = sha256(hash + data)
    }

    fun mixKey(data: ByteArray) {
        val output = hkdf(data, chainingKey, ByteArray(0), 64)
        chainingKey = output.copyOf(32)
        key = output.copyOfRange(32, 64)
        count = 0
    }

    fun mixKeyAndHash(data: ByteArray) {
        val output = hkdf(data, chainingKey, ByteArray(0), 96)
        chainingKey = output.copyOf(32)
        mixHash(output.copyOfRange(32, 64))
        key = output.copyOfRange(64, 96)
        count = 0
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = seal(key, bigEndian(count++) + ByteArray(8), plaintext, hash).also(::mixHash)

    fun decryptAndHash(ciphertext: ByteArray): ByteArray? =
        open(key, bigEndian(count++) + ByteArray(8), ciphertext, hash)?.also { mixHash(ciphertext) }

    fun trafficKeys(): Pair<ByteArray, ByteArray> {
        val output = hkdf(ByteArray(0), chainingKey, ByteArray(0), 64)
        return output.copyOf(32) to output.copyOfRange(32, 64)
    }
}

internal fun respond(
    psk: ByteArray,
    peerIdentity: ByteArray,
    message: ByteArray,
): Pair<ByteArray, Crypter>? {
    if (message.size < POINT_SIZE) return null
    val peer = message.copyOf(POINT_SIZE)
    val ephemeral = ecdhGenerate()
    val noise = Noise()
    noise.mixHash(byteArrayOf(1))
    noise.mixHash(peerIdentity)
    noise.mixKeyAndHash(psk)
    noise.mixHash(peer)
    noise.mixKey(peer)
    if (noise.decryptAndHash(message.copyOfRange(POINT_SIZE, message.size))?.isEmpty() != true) return null
    noise.mixHash(ephemeral.public)
    noise.mixKey(ephemeral.public)
    noise.mixKey(ecdh(ephemeral.private, peer) ?: return null)
    noise.mixKey(ecdh(ephemeral.private, peerIdentity) ?: return null)
    val response = ephemeral.public + noise.encryptAndHash(ByteArray(0))
    val (readKey, writeKey) = noise.trafficKeys()
    return response to Crypter(readKey, writeKey)
}

private fun bigEndian(value: Int) = ByteArray(4) { (value shr 8 * (3 - it)).toByte() }
