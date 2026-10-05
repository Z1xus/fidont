package us.z1x.fidont.keystore

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import us.z1x.fidont.BackupEntry
import us.z1x.fidont.R
import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.ctap2.KeyStore
import us.z1x.fidont.ecdhGenerate
import us.z1x.fidont.openWith
import us.z1x.fidont.random
import us.z1x.fidont.sealTo
import us.z1x.fidont.store.Credential
import us.z1x.fidont.ui.PromptActivity
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val PROVIDER = "AndroidKeyStore"
private const val AUTHENTICATORS = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
private const val CURVE = "secp256r1"
private const val GCM = "AES/GCM/NoPadding"
private const val SECRET = ".secret"
private const val WRAP = "backup"
private const val VERIFY = "verify"
private const val POINT_SIZE = 65
private const val SECRET_SIZE = 32
private const val IV_SIZE = 12
private const val TAG_BITS = 128

class AndroidKeyStore(
    private val context: Context,
) : KeyStore {
    private val store =
        java.security.KeyStore
            .getInstance(PROVIDER)
            .apply { load(null) }
    private val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val copies = File(context.filesDir, "backup")

    // the public backup key, then the private one under the keystore key
    private val wrap = File(copies, "key")

    val backup get() = wrap.exists()

    val secure get() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun generate(id: ByteArray): ByteArray? {
        if (!secure) return null
        val pair =
            if (backup) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC).run {
                    initialize(ECGenParameterSpec(CURVE))
                    generateKeyPair().also { restore(id, it.private.encoded, it.public.encoded, random(SECRET_SIZE)) }
                }
            } else {
                val alias = id.toHexString()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).run {
                    init(KeyGenParameterSpec.Builder(alias + SECRET, KeyProperties.PURPOSE_SIGN).setIsStrongBoxBacked(strongBox).build())
                    generateKey()
                }
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                    initialize(
                        KeyGenParameterSpec
                            .Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setUserAuthenticationRequired(true)
                            .setUserAuthenticationParameters(0, AUTHENTICATORS)
                            .setIsStrongBoxBacked(strongBox)
                            .build(),
                    )
                    generateKeyPair()
                }
            }
        return point(pair.public)
    }

    override fun publicKey(id: ByteArray): ByteArray? = store.getCertificate(id.toHexString())?.publicKey?.let(::point)

    override suspend fun verify(): Boolean {
        if (!secure) return false
        generateUnlockKey(VERIFY)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, store.getKey(VERIFY, null)) }
        return PromptActivity.unlock(context, R.string.prompt_verify, cipher) != null
    }

    override suspend fun sign(
        credential: Credential,
        data: ByteArray,
        registering: Boolean,
    ): ByteArray? {
        val signature = Signature.getInstance("SHA256withECDSA")
        try {
            signature.initSign(store.getKey(credential.id.toHexString(), null) as PrivateKey?)
        } catch (_: InvalidKeyException) {
            return null
        }
        return PromptActivity.authenticate(context, credential, registering, signature)?.run {
            update(data)
            sign()
        }
    }

    override fun hmac(
        id: ByteArray,
        salt: ByteArray,
    ): ByteArray? {
        val key = store.getKey(id.toHexString() + SECRET, null) ?: return null
        return Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            doFinal(salt)
        }
    }

    override fun delete(id: ByteArray) {
        val alias = id.toHexString()
        store.deleteEntry(alias)
        store.deleteEntry(alias + SECRET)
        File(copies, alias).delete()
    }

    fun restore(
        id: ByteArray,
        key: ByteArray,
        publicKey: ByteArray,
        secret: ByteArray,
    ) {
        val alias = id.toHexString()
        val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
        val pair = KeyPair(factory.generatePublic(X509EncodedKeySpec(publicKey)), factory.generatePrivate(PKCS8EncodedKeySpec(key)))
        store.setEntry(
            alias,
            java.security.KeyStore.PrivateKeyEntry(pair.private, arrayOf(certificate(pair))),
            KeyProtection
                .Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, AUTHENTICATORS)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
        store.setEntry(
            alias + SECRET,
            java.security.KeyStore.SecretKeyEntry(SecretKeySpec(secret, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setIsStrongBoxBacked(strongBox).build(),
        )
        if (backup) {
            val public = wrap.readBytes().copyOf(POINT_SIZE)
            File(copies, alias).writeBytes(sealTo(public, Cbor.encode(listOf(key, publicKey, secret))))
        }
    }

    suspend fun setBackup(enabled: Boolean) {
        if (!enabled) {
            copies.deleteRecursively()
            store.deleteEntry(WRAP)
            return
        }
        if (!secure) return
        generateUnlockKey(WRAP)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, store.getKey(WRAP, null)) }
        val unlocked = PromptActivity.unlock(context, R.string.prompt_backup, cipher) ?: return
        val key = ecdhGenerate()
        copies.mkdirs()
        wrap.writeBytes(key.public + unlocked.iv + unlocked.doFinal(key.private))
    }

    fun exportable(id: ByteArray) = File(copies, id.toHexString()).exists()

    suspend fun export(credentials: List<Credential>): List<BackupEntry>? {
        val private = unlock(R.string.prompt_export) ?: return null
        val public = wrap.readBytes().copyOf(POINT_SIZE)
        return credentials.mapNotNull { credential ->
            val copy = File(copies, credential.id.toHexString()).takeIf(File::exists) ?: return@mapNotNull null
            val parts = Cbor.decode(openWith(private, public, copy.readBytes()) ?: return@mapNotNull null) as List<*>
            BackupEntry(credential, parts[0] as ByteArray, parts[1] as ByteArray, parts[2] as ByteArray)
        }
    }

    private suspend fun unlock(title: Int): ByteArray? {
        val file = wrap.readBytes()
        val cipher = Cipher.getInstance(GCM)
        try {
            cipher.init(Cipher.DECRYPT_MODE, store.getKey(WRAP, null), GCMParameterSpec(TAG_BITS, file, POINT_SIZE, IV_SIZE))
        } catch (_: InvalidKeyException) {
            return null
        }
        val unlocked = PromptActivity.unlock(context, title, cipher) ?: return null
        return unlocked.doFinal(file, POINT_SIZE + IV_SIZE, file.size - POINT_SIZE - IV_SIZE)
    }

    private fun generateUnlockKey(alias: String) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, AUTHENTICATORS)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            generateKey()
        }
    }

    // the X.509 encoding of a P-256 key ends with the uncompressed point
    private fun point(key: PublicKey) = key.encoded.takeLast(POINT_SIZE).toByteArray()
}
