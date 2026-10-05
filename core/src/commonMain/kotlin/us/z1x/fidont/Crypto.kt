package us.z1x.fidont

expect fun random(size: Int): ByteArray

expect fun sha256(data: ByteArray): ByteArray

expect fun hmac(
    key: ByteArray,
    data: ByteArray,
): ByteArray

// no padding
expect fun aesCbc(
    encrypt: Boolean,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray

expect fun pbkdf2(
    password: String,
    salt: ByteArray,
    iterations: Int,
): ByteArray

expect fun seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray

expect fun open(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray?

class EcdhKey(
    val private: ByteArray,
    val public: ByteArray,
)

expect fun ecdhGenerate(): EcdhKey

expect fun ecdh(
    private: ByteArray,
    peer: ByteArray,
): ByteArray?

expect fun p256Decompress(point: ByteArray): ByteArray?

fun hkdf(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    size: Int,
): ByteArray {
    val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    var block = ByteArray(0)
    var output = ByteArray(0)
    var counter = 1
    while (output.size < size) {
        block = hmac(prk, block + info + counter++.toByte())
        output += block
    }
    return output.copyOf(size)
}
