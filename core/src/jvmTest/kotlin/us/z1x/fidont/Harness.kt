package us.z1x.fidont

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import us.z1x.fidont.ctap2.Authenticator
import us.z1x.fidont.ctap2.KeyStore
import us.z1x.fidont.store.Credential
import us.z1x.fidont.store.Database
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

private const val POINT_SIZE = 65

private class SoftwareKeyStore : KeyStore {
    private val pairs = HashMap<String, KeyPair>()
    private val seed = random(32)

    override fun generate(id: ByteArray): ByteArray? {
        pairs[id.toHexString()] =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
        return publicKey(id)
    }

    override suspend fun sign(
        credential: Credential,
        data: ByteArray,
        registering: Boolean,
    ): ByteArray? =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pairs.getValue(credential.id.toHexString()).private)
            update(data)
            sign()
        }

    override fun hmac(
        id: ByteArray,
        salt: ByteArray,
    ): ByteArray? = us.z1x.fidont.hmac(seed + id, salt)

    override fun publicKey(id: ByteArray): ByteArray? =
        pairs[id.toHexString()]
            ?.public
            ?.encoded
            ?.takeLast(POINT_SIZE)
            ?.toByteArray()

    override suspend fun verify() = true

    override fun delete(id: ByteArray) {
        pairs.remove(id.toHexString())
    }
}

// serves length-prefixed ctap2 requests from conformance.py
suspend fun main() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create)
    val authenticator = Authenticator(SoftwareKeyStore(), Database(driver).credentialQueries) { it.first() }
    val input = DataInputStream(System.`in`)
    val output = DataOutputStream(System.out)
    while (true) {
        val request = ByteArray(input.readUnsignedShort()).also(input::readFully)
        val response = authenticator.handle(request)
        output.writeShort(response.size)
        output.write(response)
        output.flush()
    }
}
