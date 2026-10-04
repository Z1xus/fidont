package us.z1x.fidont.keystore

import java.security.KeyPair
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

private const val INTEGER = 0x02
private const val BIT_STRING = 0x03
private const val OBJECT_ID = 0x06
private const val UTF8_STRING = 0x0c
private const val UTC_TIME = 0x17
private const val SEQUENCE = 0x30
private const val SET = 0x31

private val ECDSA_WITH_SHA256 = "2a8648ce3d040302".hexToByteArray()
private val COMMON_NAME = "550403".hexToByteArray()

// the keystore does not import a private key without a certificate, nothing reads this one
fun certificate(pair: KeyPair): Certificate {
    val algorithm = der(SEQUENCE, der(OBJECT_ID, ECDSA_WITH_SHA256))
    val name = der(SEQUENCE, der(SET, der(SEQUENCE, der(OBJECT_ID, COMMON_NAME), der(UTF8_STRING, "fidont".toByteArray()))))
    val validity = der(SEQUENCE, der(UTC_TIME, "700101000000Z".toByteArray()), der(UTC_TIME, "491231235959Z".toByteArray()))
    val body = der(SEQUENCE, der(INTEGER, byteArrayOf(1)), algorithm, name, validity, name, pair.public.encoded)
    val signature =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(body)
            sign()
        }
    val encoded = der(SEQUENCE, body, algorithm, der(BIT_STRING, byteArrayOf(0) + signature))
    return CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream())
}

private fun der(
    tag: Int,
    vararg parts: ByteArray,
): ByteArray {
    val content = parts.fold(ByteArray(0), ByteArray::plus)
    val length =
        when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
        }
    return byteArrayOf(tag.toByte()) + length + content
}
