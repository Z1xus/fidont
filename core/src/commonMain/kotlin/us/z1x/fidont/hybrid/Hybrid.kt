package us.z1x.fidont.hybrid

import us.z1x.fidont.aesCbc
import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.ctap2.Authenticator
import us.z1x.fidont.ctap2.GET_ASSERTION
import us.z1x.fidont.ctap2.MAKE_CREDENTIAL
import us.z1x.fidont.ctap2.OK
import us.z1x.fidont.hkdf
import us.z1x.fidont.hmac
import us.z1x.fidont.random
import us.z1x.fidont.sha256

// browsers hash this id to cable.ahhkeysummo2d.com, 0 is Google's cable.ua5v.com
private const val RELAY = 64322

private const val EID_KEY = 1
private const val TUNNEL_ID = 2
private const val PSK = 3

private const val SHUTDOWN = 0
private const val CTAP = 1
private const val UPDATE = 2

interface Tunnel {
    val routingId: ByteArray

    suspend fun send(message: ByteArray)

    suspend fun receive(): ByteArray?
}

class Hybrid(
    private val authenticator: Authenticator,
) {
    suspend fun serve(
        qr: Qr,
        connect: suspend (url: String) -> Tunnel?,
        advertise: (ByteArray) -> Unit,
    ): Boolean {
        val tunnelId = derive(qr.secret, ByteArray(0), TUNNEL_ID, 16)
        val tunnel = connect("wss://${relayDomain(RELAY)}/cable/new/${tunnelId.toHexString()}") ?: return false
        val eid = byteArrayOf(0) + random(10) + tunnel.routingId + byteArrayOf(RELAY.toByte(), (RELAY shr 8).toByte())
        advertise(advert(eid, derive(qr.secret, ByteArray(0), EID_KEY, 64)))
        val handshake = tunnel.receive() ?: return false
        val (response, crypter) = respond(derive(qr.secret, eid, PSK, 32), qr.peerIdentity, handshake) ?: return false
        tunnel.send(response)
        tunnel.send(crypter.encrypt(Cbor.encode(mapOf(1 to authenticator.info, 3 to listOf("ctap")))))
        var done = false
        while (true) {
            val message = crypter.decrypt(tunnel.receive() ?: return done) ?: return done
            when (message.firstOrNull()?.toInt()) {
                CTAP -> {
                    val reply = authenticator.handle(message.copyOfRange(1, message.size))
                    tunnel.send(crypter.encrypt(byteArrayOf(CTAP.toByte()) + reply))
                    val command = message.getOrNull(1)?.toInt()
                    if (command == MAKE_CREDENTIAL || command == GET_ASSERTION) done = reply[0].toInt() == OK
                }

                UPDATE -> {}

                else -> {
                    return done
                }
            }
        }
    }
}

private fun relayDomain(id: Int): String {
    val digest = sha256("caBLEv2 tunnel server domain".encodeToByteArray() + byteArrayOf(id.toByte(), (id shr 8).toByte(), 0))
    var value = (7 downTo 0).fold(0UL) { number, index -> number shl 8 or digest[index].toUByte().toULong() }
    val tld = listOf("com", "org", "net", "info")[(value and 3u).toInt()]
    value = value shr 2
    val name =
        buildString {
            while (value != 0UL) {
                append("abcdefghijklmnopqrstuvwxyz234567"[(value and 31u).toInt()])
                value = value shr 5
            }
        }
    return "cable.$name.$tld"
}

private fun derive(
    secret: ByteArray,
    salt: ByteArray,
    type: Int,
    size: Int,
) = hkdf(secret, salt, byteArrayOf(type.toByte(), 0, 0, 0), size)

private fun advert(
    eid: ByteArray,
    key: ByteArray,
): ByteArray {
    val encrypted = aesCbc(true, key.copyOf(32), eid)
    return encrypted + hmac(key.copyOfRange(32, 64), encrypted).copyOf(4)
}
