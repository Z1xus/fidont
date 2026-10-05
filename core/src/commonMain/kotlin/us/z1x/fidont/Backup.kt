package us.z1x.fidont

import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.store.Credential

private const val VERSION = 1
private const val AUTOMATIC = 2
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
    val list = Cbor.encode(entries.map { fields(it.credential) + listOf(it.key, it.publicKey, it.secret) })
    if (password.isEmpty()) return Cbor.encode(mapOf(1 to VERSION, 2 to list))
    val salt = random(SALT_SIZE)
    return Cbor.encode(mapOf(1 to VERSION, 3 to salt, 4 to seal(pbkdf2(password, salt, ITERATIONS), NONCE, list, ByteArray(0))))
}

fun wrapBackupKey(
    private: ByteArray,
    password: String,
): ByteArray {
    val salt = random(SALT_SIZE)
    return salt + seal(pbkdf2(password, salt, ITERATIONS), NONCE, private, ByteArray(0))
}

// the phone holds no secret for this file, so it can write one without a prompt
fun encodeAutomaticBackup(
    wrapped: ByteArray,
    public: ByteArray,
    copies: List<Pair<Credential, ByteArray>>,
): ByteArray {
    val list = Cbor.encode(copies.map { (credential, copy) -> fields(credential) + copy })
    return Cbor.encode(
        mapOf(
            1 to AUTOMATIC,
            3 to wrapped.copyOf(SALT_SIZE),
            4 to wrapped.copyOfRange(SALT_SIZE, wrapped.size),
            5 to public,
            6 to sealTo(public, list),
        ),
    )
}

fun backupEncrypted(file: ByteArray): Boolean? =
    try {
        (Cbor.decode(file) as? Map<*, *>)?.takeIf { it[1L] == VERSION.toLong() || it[1L] == AUTOMATIC.toLong() }?.let { 2L !in it }
    } catch (_: IllegalArgumentException) {
        null
    }

fun decodeBackup(
    file: ByteArray,
    password: String,
): List<BackupEntry>? =
    try {
        val map = Cbor.decode(file) as Map<*, *>
        val opened =
            map[2L] as ByteArray?
                ?: open(pbkdf2(password, map[3L] as ByteArray, ITERATIONS), NONCE, map[4L] as ByteArray, ByteArray(0))
                ?: return null
        val public = map[5L] as ByteArray?
        val list = if (public == null) opened else openWith(opened, public, map[6L] as ByteArray)!!
        (Cbor.decode(list) as List<*>).map {
            val item = it as List<*>
            val keys = if (public == null) item.drop(6) else Cbor.decode(openWith(opened, public, item[6] as ByteArray)!!) as List<*>
            BackupEntry(
                Credential(
                    item[0] as ByteArray,
                    item[1] as String,
                    item[2] as ByteArray,
                    item[3] as String,
                    item[4] as String,
                    item[5] as Boolean,
                ),
                keys[0] as ByteArray,
                keys[1] as ByteArray,
                keys[2] as ByteArray,
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ClassCastException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: NullPointerException) {
        null
    }

private fun fields(credential: Credential) =
    listOf(
        credential.id,
        credential.rpId,
        credential.userId,
        credential.userName,
        credential.displayName,
        credential.discoverable,
    )

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
