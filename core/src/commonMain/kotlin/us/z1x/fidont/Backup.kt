package us.z1x.fidont

import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.store.Credential

private const val VERSION = 1
private const val ITERATIONS = 600_000
private const val SALT_SIZE = 16
private const val KEY_SIZE = 32
private const val POINT_SIZE = 65

// every key here encrypts one message
private val NONCE = ByteArray(12)

class BackupEntry(
    val credential: Credential,
    val key: ByteArray,
    val publicKey: ByteArray,
    val secret: ByteArray,
)

fun encodeBackup(
    entries: List<BackupEntry>,
    password: String,
): ByteArray {
    val list =
        Cbor.encode(
            entries.map {
                listOf(
                    it.credential.id,
                    it.credential.rpId,
                    it.credential.userId,
                    it.credential.userName,
                    it.credential.displayName,
                    it.credential.discoverable,
                    it.key,
                    it.publicKey,
                    it.secret,
                )
            },
        )
    if (password.isEmpty()) return Cbor.encode(mapOf(1 to VERSION, 2 to list))
    val salt = random(SALT_SIZE)
    return Cbor.encode(mapOf(1 to VERSION, 3 to salt, 4 to seal(pbkdf2(password, salt, ITERATIONS), NONCE, list, ByteArray(0))))
}

fun backupEncrypted(file: ByteArray): Boolean? =
    try {
        (Cbor.decode(file) as? Map<*, *>)?.takeIf { it[1L] == VERSION.toLong() }?.let { 2L !in it }
    } catch (_: IllegalArgumentException) {
        null
    }

fun decodeBackup(
    file: ByteArray,
    password: String,
): List<BackupEntry>? =
    try {
        val map = Cbor.decode(file) as Map<*, *>
        val list =
            map[2L] as ByteArray? ?: open(pbkdf2(password, map[3L] as ByteArray, ITERATIONS), NONCE, map[4L] as ByteArray, ByteArray(0))
        (list?.let(Cbor::decode) as List<*>?)?.map {
            val item = it as List<*>
            BackupEntry(
                Credential(
                    item[0] as ByteArray,
                    item[1] as String,
                    item[2] as ByteArray,
                    item[3] as String,
                    item[4] as String,
                    item[5] as Boolean,
                ),
                item[6] as ByteArray,
                item[7] as ByteArray,
                item[8] as ByteArray,
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ClassCastException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

fun sealTo(
    public: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val ephemeral = ecdhGenerate()
    return ephemeral.public + seal(wrapKey(ecdh(ephemeral.private, public)!!, ephemeral.public, public), NONCE, plaintext, ByteArray(0))
}

fun openWith(
    private: ByteArray,
    public: ByteArray,
    sealed: ByteArray,
): ByteArray? {
    if (sealed.size < POINT_SIZE) return null
    val ephemeral = sealed.copyOf(POINT_SIZE)
    val shared = ecdh(private, ephemeral) ?: return null
    return open(wrapKey(shared, ephemeral, public), NONCE, sealed.copyOfRange(POINT_SIZE, sealed.size), ByteArray(0))
}

private fun wrapKey(
    shared: ByteArray,
    ephemeral: ByteArray,
    public: ByteArray,
) = hkdf(shared, ephemeral + public, "fidont backup".encodeToByteArray(), KEY_SIZE)
