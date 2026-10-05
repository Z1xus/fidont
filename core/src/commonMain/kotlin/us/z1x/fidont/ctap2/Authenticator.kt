package us.z1x.fidont.ctap2

import us.z1x.fidont.aesCbc
import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.ecdh
import us.z1x.fidont.ecdhGenerate
import us.z1x.fidont.hkdf
import us.z1x.fidont.hmac
import us.z1x.fidont.random
import us.z1x.fidont.sha256
import us.z1x.fidont.store.Credential
import us.z1x.fidont.store.CredentialQueries
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

const val MAKE_CREDENTIAL = 0x01
const val GET_ASSERTION = 0x02
private const val GET_INFO = 0x04
private const val CLIENT_PIN = 0x06
private const val CREDENTIAL_MANAGEMENT = 0x0a
private const val SELECTION = 0x0b

const val OK = 0x00
private const val INVALID_COMMAND = 0x01
private const val INVALID_PARAMETER = 0x02
private const val INVALID_LENGTH = 0x03
private const val INVALID_CBOR = 0x12
private const val MISSING_PARAMETER = 0x14
private const val CREDENTIAL_EXCLUDED = 0x19
const val UNSUPPORTED_ALGORITHM = 0x26
private const val OPERATION_DENIED = 0x27
private const val NO_CREDENTIALS = 0x2e
private const val NOT_ALLOWED = 0x30
private const val PIN_AUTH_INVALID = 0x33
private const val INVALID_SUBCOMMAND = 0x3e

private const val USER_PRESENT = 0x01
private const val USER_VERIFIED = 0x04
private const val ATTESTED = 0x40
private const val EXTENSIONS = 0x80

const val ES256 = -7L
private const val ECDH_ES_HKDF_256 = -25L
private const val HMAC_SECRET = "hmac-secret"
private const val KEY_AGREEMENT = 2L
private const val UV_TOKEN = 6L
private const val UV_RETRIES = 7L
private const val UV_ATTEMPTS = 3
private const val MAKE = 1L
private const val GET = 2L
private const val MANAGE = 4L
private const val METADATA = 1L
private const val RPS = 2L
private const val NEXT_RP = 3L
private const val CREDENTIALS = 4L
private const val NEXT_CREDENTIAL = 5L
private const val DELETE = 6L
private const val UPDATE = 7L
private const val SPARE_CREDENTIALS = 1000
private const val SALT_SIZE = 32
private const val KEY_SIZE = 32
private const val BLOCK_SIZE = 16
private const val CREDENTIAL_ID_SIZE = 32
private const val MAX_MESSAGE_SIZE = 4096
private const val MAX_ALLOW_LIST = 16
private val PIN_PROTOCOLS = listOf(2, 1)
private val TOKEN_LIFETIME = 10.minutes
private val AAGUID = "f1d0badc0def4f1d8badc0def1d0badc".hexToByteArray()

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

private class Token(
    val value: ByteArray,
    val protocol: Long,
    val permissions: Long,
    val rpId: String?,
) {
    val expires = TimeSource.Monotonic.markNow() + TOKEN_LIFETIME
}

private class Shared(
    private val protocol: Long,
    point: ByteArray,
) {
    private val authKey = if (protocol == 2L) hkdf(point, ByteArray(0), "CTAP2 HMAC key".encodeToByteArray(), KEY_SIZE) else sha256(point)
    private val cipherKey = if (protocol == 2L) hkdf(point, ByteArray(0), "CTAP2 AES key".encodeToByteArray(), KEY_SIZE) else authKey

    // protocol 1 has a zero iv and does not send it
    private val ivSize = if (protocol == 2L) BLOCK_SIZE else 0

    fun verify(
        message: ByteArray,
        tag: ByteArray,
    ) = verified(protocol, authKey, message, tag)

    fun encrypt(data: ByteArray): ByteArray {
        val iv = random(ivSize)
        return iv + aesCbc(true, cipherKey, iv.copyOf(BLOCK_SIZE), data)
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < ivSize || data.size % BLOCK_SIZE != 0) throw CtapException(INVALID_LENGTH)
        return aesCbc(false, cipherKey, data.copyOf(ivSize).copyOf(BLOCK_SIZE), data.copyOfRange(ivSize, data.size))
    }
}

private fun verified(
    protocol: Long,
    key: ByteArray,
    message: ByteArray,
    tag: ByteArray,
): Boolean {
    val expected = hmac(key, message).copyOf(if (protocol == 2L) KEY_SIZE else BLOCK_SIZE)
    // the comparison must take the same time for every tag
    return tag.size == expected.size && tag.indices.fold(0) { bits, index -> bits or (tag[index].toInt() xor expected[index].toInt()) } == 0
}

class Authenticator(
    private val keys: KeyStore,
    private val credentials: CredentialQueries,
    private val choose: suspend (List<Credential>) -> Credential?,
) {
    private val agreement = ecdhGenerate()
    private var token: Token? = null
    private var enumeration = emptyList<Map<Int, Any>>()

    val info: ByteArray =
        Cbor.encode(
            mapOf(
                1 to listOf("FIDO_2_0", "FIDO_2_1"),
                2 to listOf(HMAC_SECRET),
                3 to AAGUID,
                4 to mapOf("rk" to true, "uv" to true, "credMgmt" to true, "pinUvAuthToken" to true),
                5 to MAX_MESSAGE_SIZE,
                6 to PIN_PROTOCOLS,
                7 to MAX_ALLOW_LIST,
                8 to CREDENTIAL_ID_SIZE,
                9 to listOf("usb", "nfc", "hybrid", "internal"),
                10 to listOf(mapOf("alg" to ES256, "type" to "public-key")),
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
                    CLIENT_PIN -> Cbor.encode(clientPin(parameters ?: missing()))
                    CREDENTIAL_MANAGEMENT -> manage(parameters ?: missing()).takeIf { it.isNotEmpty() }?.let(Cbor::encode) ?: ByteArray(0)
                    SELECTION -> if (keys.verify()) ByteArray(0) else throw CtapException(OPERATION_DENIED)
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
        secret: Boolean = false,
    ): Registration {
        if (known(rpId, exclude).isNotEmpty()) throw CtapException(CREDENTIAL_EXCLUDED)
        val credential = Credential(random(CREDENTIAL_ID_SIZE), rpId, userId, userName, displayName, discoverable)
        val publicKey = keys.generate(credential.id) ?: throw CtapException(OPERATION_DENIED)
        val authData =
            sha256(rpId.encodeToByteArray()) +
                byteArrayOf((USER_PRESENT or USER_VERIFIED or ATTESTED or if (secret) EXTENSIONS else 0).toByte()) +
                ByteArray(4) +
                AAGUID +
                byteArrayOf(0, CREDENTIAL_ID_SIZE.toByte()) +
                credential.id +
                Cbor.encode(cose(publicKey, ES256)) +
                if (secret) Cbor.encode(mapOf(HMAC_SECRET to true)) else ByteArray(0)
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
        extend: (Credential) -> ByteArray? = { null },
    ): Assertion {
        val candidates = if (allow.isEmpty()) credentials.discoverable(rpId).executeAsList() else known(rpId, allow)
        val credential =
            when (candidates.size) {
                0 -> throw CtapException(NO_CREDENTIALS)
                1 -> candidates.single()
                else -> choose(candidates) ?: throw CtapException(OPERATION_DENIED)
            }
        val extensions = extend(credential)
        val flags = USER_PRESENT or USER_VERIFIED or if (extensions != null) EXTENSIONS else 0
        val authData = sha256(rpId.encodeToByteArray()) + byteArrayOf(flags.toByte()) + ByteArray(4) + (extensions ?: ByteArray(0))
        val signature =
            keys.sign(credential, authData + clientDataHash, registering = false)
                ?: throw CtapException(OPERATION_DENIED)
        return Assertion(credential, authData, signature)
    }

    fun secret(
        id: ByteArray,
        salt: ByteArray,
    ): ByteArray? = keys.hmac(id, salt)

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
        val clientDataHash = parameters[1L] as? ByteArray ?: missing()
        val rpId = rp["id"] as? String ?: missing()
        parameters[8L]?.let { authorize(it, clientDataHash, MAKE, rpId) }
        val registration =
            register(
                clientDataHash = clientDataHash,
                rpId = rpId,
                userId = user["id"] as? ByteArray ?: missing(),
                userName = user["name"] as? String ?: "",
                displayName = user["displayName"] as? String ?: "",
                discoverable = (parameters[7L] as? Map<*, *>)?.get("rk") == true,
                exclude = descriptors(parameters[5L]),
                secret = (parameters[6L] as? Map<*, *>)?.get(HMAC_SECRET) == true,
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
        parameters[6L]?.let { authorize(it, clientDataHash, GET, rpId) }
        if ((parameters[5L] as? Map<*, *>)?.get("up") == false) {
            val credential = known(rpId, allow).firstOrNull() ?: throw CtapException(NO_CREDENTIALS)
            return mapOf(
                1 to mapOf("id" to credential.id, "type" to "public-key"),
                2 to sha256(rpId.encodeToByteArray()) + ByteArray(5),
                3 to PROBE_SIGNATURE,
            )
        }
        val secret = (parameters[4L] as? Map<*, *>)?.get(HMAC_SECRET) as? Map<*, *>
        val assertion = assert(rpId, clientDataHash, allow) { credential -> secret?.let { secrets(credential, it) } }
        return mapOf(
            1 to mapOf("id" to assertion.credential.id, "type" to "public-key"),
            2 to assertion.authData,
            3 to assertion.signature,
            4 to mapOf("id" to assertion.credential.userId),
        )
    }

    private suspend fun clientPin(parameters: Map<*, *>): Map<Int, Any> =
        when (parameters[2L]) {
            KEY_AGREEMENT -> {
                mapOf(1 to cose(agreement.public, ECDH_ES_HKDF_256))
            }

            UV_RETRIES -> {
                mapOf(5 to UV_ATTEMPTS)
            }

            UV_TOKEN -> {
                val shared = shared(parameters[1L], parameters[3L])
                val permissions = parameters[9L] as? Long ?: missing()
                // a credential asks for the fingerprint itself when it signs
                if (permissions and (MAKE or GET).inv() != 0L && !keys.verify()) throw CtapException(OPERATION_DENIED)
                val issued = Token(random(KEY_SIZE), parameters[1L] as Long, permissions, parameters[10L] as? String)
                token = issued
                mapOf(2 to shared.encrypt(issued.value))
            }

            else -> {
                throw CtapException(INVALID_SUBCOMMAND)
            }
        }

    private fun manage(parameters: Map<*, *>): Map<Int, Any> {
        val command = parameters[1L] as? Long ?: missing()
        val arguments = parameters[2L] as? Map<*, *>
        if (command == NEXT_RP || command == NEXT_CREDENTIAL) return next()
        authorize(parameters[4L], byteArrayOf(command.toByte()) + (arguments?.let(Cbor::encode) ?: ByteArray(0)), MANAGE, null)
        val stored = credentials.all().executeAsList().filter { it.discoverable }
        return when (command) {
            METADATA -> {
                mapOf(1 to stored.size, 2 to SPARE_CREDENTIALS)
            }

            RPS -> {
                first(stored.map { it.rpId }.distinct().map { mapOf(3 to mapOf("id" to it), 4 to sha256(it.encodeToByteArray())) }, 5)
            }

            CREDENTIALS -> {
                val hash = arguments?.get(1L) as? ByteArray ?: missing()
                first(stored.filter { sha256(it.rpId.encodeToByteArray()).contentEquals(hash) }.mapNotNull(::entry), 9)
            }

            DELETE -> {
                remove(target(arguments))
                emptyMap()
            }

            UPDATE -> {
                val user = arguments?.get(3L) as? Map<*, *> ?: missing()
                credentials.rename(user["name"] as? String ?: "", user["displayName"] as? String ?: "", target(arguments).id)
                emptyMap()
            }

            else -> {
                throw CtapException(INVALID_SUBCOMMAND)
            }
        }
    }

    private fun first(
        items: List<Map<Int, Any>>,
        total: Int,
    ): Map<Int, Any> {
        enumeration = items.drop(1)
        return (items.firstOrNull() ?: throw CtapException(NO_CREDENTIALS)) + (total to items.size)
    }

    private fun next(): Map<Int, Any> {
        val item = enumeration.firstOrNull() ?: throw CtapException(NOT_ALLOWED)
        enumeration = enumeration.drop(1)
        return item
    }

    private fun entry(credential: Credential): Map<Int, Any>? {
        val publicKey = keys.publicKey(credential.id) ?: return null
        return mapOf(
            6 to mapOf("id" to credential.userId, "name" to credential.userName, "displayName" to credential.displayName),
            7 to mapOf("id" to credential.id, "type" to "public-key"),
            8 to cose(publicKey, ES256),
        )
    }

    private fun target(arguments: Map<*, *>?): Credential {
        val id = (arguments?.get(2L) as? Map<*, *>)?.get("id") as? ByteArray ?: missing()
        return credentials.byId(id).executeAsOneOrNull() ?: throw CtapException(NO_CREDENTIALS)
    }

    private fun authorize(
        tag: Any?,
        message: ByteArray,
        permission: Long,
        rpId: String?,
    ) {
        val granted = token?.takeIf { it.expires.hasNotPassedNow() && it.permissions and permission != 0L && (it.rpId ?: rpId) == rpId }
        if (granted == null || tag !is ByteArray || !verified(granted.protocol, granted.value, message, tag)) {
            throw CtapException(PIN_AUTH_INVALID)
        }
    }

    private fun shared(
        protocol: Any?,
        key: Any?,
    ): Shared {
        if (protocol != 1L && protocol != 2L) throw CtapException(INVALID_PARAMETER)
        val peer = key as? Map<*, *> ?: missing()
        val x = peer[-2L] as? ByteArray ?: missing()
        val y = peer[-3L] as? ByteArray ?: missing()
        return Shared(protocol as Long, ecdh(agreement.private, byteArrayOf(4) + x + y) ?: throw CtapException(INVALID_PARAMETER))
    }

    private fun secrets(
        credential: Credential,
        input: Map<*, *>,
    ): ByteArray? {
        val shared = shared(input[4L] ?: 1L, input[1L])
        val encrypted = input[2L] as? ByteArray ?: missing()
        if (!shared.verify(encrypted, input[3L] as? ByteArray ?: missing())) throw CtapException(PIN_AUTH_INVALID)
        val salts = shared.decrypt(encrypted)
        if (salts.size != SALT_SIZE && salts.size != 2 * SALT_SIZE) throw CtapException(INVALID_LENGTH)
        val outputs = salts.asList().chunked(SALT_SIZE).map { keys.hmac(credential.id, it.toByteArray()) ?: return null }
        return Cbor.encode(mapOf(HMAC_SECRET to shared.encrypt(outputs.reduce(ByteArray::plus))))
    }

    private fun known(
        rpId: String,
        ids: List<ByteArray>,
    ): List<Credential> = ids.mapNotNull { credentials.byId(it).executeAsOneOrNull() }.filter { it.rpId == rpId }

    private fun descriptors(list: Any?): List<ByteArray> =
        (list as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("id") as? ByteArray }

    private fun cose(
        publicKey: ByteArray,
        algorithm: Long,
    ): Map<Int, Any> =
        mapOf(
            1 to 2,
            3 to algorithm,
            -1 to 1,
            -2 to publicKey.copyOfRange(1, 33),
            -3 to publicKey.copyOfRange(33, 65),
        )

    private fun missing(): Nothing = throw CtapException(MISSING_PARAMETER)
}
