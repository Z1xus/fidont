package us.z1x.fidont.ctap2

import us.z1x.fidont.store.Credential

interface KeyStore {
    fun generate(id: ByteArray): ByteArray?

    suspend fun sign(
        credential: Credential,
        data: ByteArray,
        registering: Boolean,
    ): ByteArray?

    fun hmac(
        id: ByteArray,
        salt: ByteArray,
    ): ByteArray?

    fun publicKey(id: ByteArray): ByteArray?

    suspend fun verify(): Boolean

    fun delete(id: ByteArray)
}
