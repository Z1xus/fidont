package us.z1x.fidont

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val p256: ECParameterSpec =
    AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

actual fun random(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun hmac(
    key: ByteArray,
    data: ByteArray,
): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

actual fun aesEncryptBlock(
    key: ByteArray,
    block: ByteArray,
): ByteArray =
    Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        doFinal(block)
    }

actual fun seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

actual fun open(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray? =
    try {
        gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
    } catch (_: GeneralSecurityException) {
        null
    }

actual fun ecdhGenerate(): EcdhKey {
    val pair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(p256)
            generateKeyPair()
        }
    return EcdhKey(field((pair.private as ECPrivateKey).s), encode((pair.public as ECPublicKey).w))
}

actual fun ecdh(
    private: ByteArray,
    peer: ByteArray,
): ByteArray? =
    try {
        val factory = KeyFactory.getInstance("EC")
        KeyAgreement.getInstance("ECDH").run {
            init(factory.generatePrivate(ECPrivateKeySpec(BigInteger(1, private), p256)))
            doPhase(factory.generatePublic(ECPublicKeySpec(decode(peer), p256)), true)
            generateSecret()
        }
    } catch (_: GeneralSecurityException) {
        null
    }

actual fun p256Decompress(point: ByteArray): ByteArray? {
    if (point.size != 33 || point[0] !in 2..3) return null
    val p = (p256.curve.field as ECFieldFp).p
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val square = (x.pow(3) + p256.curve.a * x + p256.curve.b).mod(p)
    // p = 3 mod 4, so a square root is a power
    val root = square.modPow((p + BigInteger.ONE).shiftRight(2), p)
    if ((root * root).mod(p) != square) return null
    val y = if (root.testBit(0) == (point[0].toInt() == 3)) root else p - root
    return encode(ECPoint(x, y))
}

private fun gcm(
    mode: Int,
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
    }

private fun field(value: BigInteger): ByteArray =
    value.toByteArray().let { bytes ->
        ByteArray(32).also { bytes.copyInto(it, maxOf(0, 32 - bytes.size), maxOf(0, bytes.size - 32)) }
    }

private fun encode(point: ECPoint): ByteArray = byteArrayOf(4) + field(point.affineX) + field(point.affineY)

private fun decode(point: ByteArray): ECPoint {
    if (point.size != 65 || point[0].toInt() != 4) throw GeneralSecurityException()
    return ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65)))
}
