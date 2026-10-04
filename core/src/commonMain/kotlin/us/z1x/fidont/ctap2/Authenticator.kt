package us.z1x.fidont.ctap2

import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.random
import us.z1x.fidont.sha256
import us.z1x.fidont.store.Credential
import us.z1x.fidont.store.CredentialQueries

private const val MAKE_CREDENTIAL = 0x01
private const val GET_ASSERTION = 0x02
private const val GET_INFO = 0x04

const val OK = 0x00
private const val INVALID_COMMAND = 0x01
private const val INVALID_CBOR = 0x12
private const val MISSING_PARAMETER = 0x14
private const val CREDENTIAL_EXCLUDED = 0x19
private const val UNSUPPORTED_ALGORITHM = 0x26
private const val OPERATION_DENIED = 0x27
private const val NO_CREDENTIALS = 0x2e

private const val USER_PRESENT = 0x01
private const val USER_VERIFIED = 0x04
private const val ATTESTED = 0x40

const val ES256 = -7L
private const val CREDENTIAL_ID_SIZE = 32
private const val MAX_MESSAGE_SIZE = 4096
private const val MAX_ALLOW_LIST = 16
private val AAGUID = "c7a14cb5fe7040498b465283497c355a".hexToByteArray()

// a probe cannot unlock the key, and the platform only reads which credential answered
private val PROBE_SIGNATURE = "3006020101020101".hexToByteArray()

class CtapException(
    val status: Int,
) : Exception()

class Registration(
    val credential: Credential,
    val publicKey: ByteArray,
    val authData: ByteArray,
    val signature: ByteArray,
)

class Assertion(
    val credential: Credential,
    val authData: ByteArray,
    val signature: ByteArray,
)

class Authenticator(
    private val keys: KeyStore,
    private val credentials: CredentialQueries,
    private val choose: suspend (List<Credential>) -> Credential?,
) {
    val info: ByteArray =
        Cbor.encode(
            mapOf(
                1 to listOf("FIDO_2_0"),
                3 to AAGUID,
                4 to mapOf("rk" to true, "uv" to true),
                5 to MAX_MESSAGE_SIZE,
                7 to MAX_ALLOW_LIST,
                8 to CREDENTIAL_ID_SIZE,
                9 to listOf("usb", "nfc", "hybrid", "internal"),
            ),
        )

    suspend fun handle(request: ByteArray): ByteArray =
        try {
            val parameters = if (request.size > 1) Cbor.decode(request, 1) as? Map<*, *> else null
            val response =
                when (request.firstOrNull()?.toInt()) {
                    MAKE_CREDENTIAL -> Cbor.encode(makeCredential(parameters ?: missing()))
                    GET_ASSERTION -> Cbor.encode(getAssertion(parameters ?: missing()))
                    GET_INFO -> info
                    else -> throw CtapException(INVALID_COMMAND)
                }
            byteArrayOf(OK.toByte()) + response
        } catch (e: CtapException) {
            byteArrayOf(e.status.toByte())
        } catch (_: IllegalArgumentException) {
            byteArrayOf(INVALID_CBOR.toByte())
        }

    suspend fun register(
        clientDataHash: ByteArray,
        rpId: String,
        userId: ByteArray,
        userName: String,
        displayName: String,
        discoverable: Boolean,
        exclude: List<ByteArray>,
    ): Registration {
        if (known(rpId, exclude).isNotEmpty()) throw CtapException(CREDENTIAL_EXCLUDED)
        val credential = Credential(random(CREDENTIAL_ID_SIZE), rpId, userId, userName, displayName, discoverable)
        val publicKey = keys.generate(credential.id)
        val authData =
            sha256(rpId.encodeToByteArray()) +
                byteArrayOf((USER_PRESENT or USER_VERIFIED or ATTESTED).toByte()) +
                ByteArray(4) +
                AAGUID +
                byteArrayOf(0, CREDENTIAL_ID_SIZE.toByte()) +
                credential.id +
                cose(publicKey)
        val signature = keys.sign(credential, authData + clientDataHash, registering = true)
        if (signature == null) {
            keys.delete(credential.id)
            throw CtapException(OPERATION_DENIED)
        }
        if (discoverable) {
            credentials
                .discoverable(rpId)
                .executeAsList()
                .filter { it.userId.contentEquals(userId) }
                .forEach(::remove)
        }
        credentials.insert(credential)
        return Registration(credential, publicKey, authData, signature)
    }

    suspend fun assert(
        rpId: String,
        clientDataHash: ByteArray,
        allow: List<ByteArray>,
    ): Assertion {
        val candidates = if (allow.isEmpty()) credentials.discoverable(rpId).executeAsList() else known(rpId, allow)
        val credential =
            when (candidates.size) {
                0 -> throw CtapException(NO_CREDENTIALS)
                1 -> candidates.single()
                else -> choose(candidates) ?: throw CtapException(OPERATION_DENIED)
            }
        val authData = sha256(rpId.encodeToByteArray()) + byteArrayOf((USER_PRESENT or USER_VERIFIED).toByte()) + ByteArray(4)
        val signature =
            keys.sign(credential, authData + clientDataHash, registering = false)
                ?: throw CtapException(OPERATION_DENIED)
        return Assertion(credential, authData, signature)
    }

    fun remove(credential: Credential) {
        keys.delete(credential.id)
        credentials.delete(credential.id)
    }

    private suspend fun makeCredential(parameters: Map<*, *>): Map<Int, Any> {
        val rp = parameters[2L] as? Map<*, *> ?: missing()
        val user = parameters[3L] as? Map<*, *> ?: missing()
        val algorithms = parameters[4L] as? List<*> ?: missing()
        if (algorithms.none { it is Map<*, *> && it["alg"] == ES256 && it["type"] == "public-key" }) {
            throw CtapException(UNSUPPORTED_ALGORITHM)
        }
        val registration =
            register(
                clientDataHash = parameters[1L] as? ByteArray ?: missing(),
                rpId = rp["id"] as? String ?: missing(),
                userId = user["id"] as? ByteArray ?: missing(),
                userName = user["name"] as? String ?: "",
                displayName = user["displayName"] as? String ?: "",
                discoverable = (parameters[7L] as? Map<*, *>)?.get("rk") == true,
                exclude = descriptors(parameters[5L]),
            )
        return mapOf(
            1 to "packed",
            2 to registration.authData,
            3 to mapOf("alg" to ES256, "sig" to registration.signature),
        )
    }

    private suspend fun getAssertion(parameters: Map<*, *>): Map<Int, Any> {
        val rpId = parameters[1L] as? String ?: missing()
        val clientDataHash = parameters[2L] as? ByteArray ?: missing()
        val allow = descriptors(parameters[3L])
        if ((parameters[5L] as? Map<*, *>)?.get("up") == false) {
            val credential = known(rpId, allow).firstOrNull() ?: throw CtapException(NO_CREDENTIALS)
            return mapOf(
                1 to mapOf("id" to credential.id, "type" to "public-key"),
                2 to sha256(rpId.encodeToByteArray()) + ByteArray(5),
                3 to PROBE_SIGNATURE,
            )
        }
        val assertion = assert(rpId, clientDataHash, allow)
        return mapOf(
            1 to mapOf("id" to assertion.credential.id, "type" to "public-key"),
            2 to assertion.authData,
            3 to assertion.signature,
            4 to mapOf("id" to assertion.credential.userId),
        )
    }

    private fun known(
        rpId: String,
        ids: List<ByteArray>,
    ): List<Credential> = ids.mapNotNull { credentials.byId(it).executeAsOneOrNull() }.filter { it.rpId == rpId }

    private fun descriptors(list: Any?): List<ByteArray> =
        (list as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("id") as? ByteArray }

    private fun cose(publicKey: ByteArray): ByteArray =
        Cbor.encode(
            mapOf(
                1 to 2,
                3 to ES256,
                -1 to 1,
                -2 to publicKey.copyOfRange(1, 33),
                -3 to publicKey.copyOfRange(33, 65),
            ),
        )

    private fun missing(): Nothing = throw CtapException(MISSING_PARAMETER)
}
