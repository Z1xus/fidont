package us.z1x.fidont.keystore

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import us.z1x.fidont.ctap2.KeyStore
import us.z1x.fidont.store.Credential
import us.z1x.fidont.ui.PromptActivity
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

private const val PROVIDER = "AndroidKeyStore"
private const val POINT_SIZE = 65

class AndroidKeyStore(
    private val context: Context,
) : KeyStore {
    private val store =
        java.security.KeyStore
            .getInstance(PROVIDER)
            .apply { load(null) }

    override fun generate(id: ByteArray): ByteArray? {
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return null
        val spec =
            KeyGenParameterSpec
                .Builder(id.toHexString(), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setIsStrongBoxBacked(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
                .build()
        val pair =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        // the X.509 encoding of a P-256 key ends with the uncompressed point
        return pair.public.encoded
            .takeLast(POINT_SIZE)
            .toByteArray()
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

    override fun delete(id: ByteArray) = store.deleteEntry(id.toHexString())
}
