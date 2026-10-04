package us.z1x.fidont.transport.provider

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import us.z1x.fidont.app
import us.z1x.fidont.cbor.Cbor
import us.z1x.fidont.ctap2.CtapException
import us.z1x.fidont.ctap2.ES256
import us.z1x.fidont.sha256

private const val CREATE = "webauthn.create"
private const val GET = "webauthn.get"
private val TRANSPORTS = JSONArray(listOf("internal", "hybrid", "usb", "nfc"))

// X.509 header of a P-256 public key, the uncompressed point follows
private val SPKI_HEADER = "3059301306072a8648ce3d020106082a8648ce3d030107034200".hexToByteArray()

class ProviderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val create = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val get = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        lifecycleScope.launch(Dispatchers.Default) {
            val result = Intent()
            try {
                when {
                    create != null -> PendingIntentHandler.setCreateCredentialResponse(result, create(create))
                    get != null -> PendingIntentHandler.setGetCredentialResponse(result, get(get))
                }
                setResult(RESULT_OK, result)
            } catch (_: CtapException) {
                setResult(RESULT_CANCELED)
            } catch (_: IllegalStateException) {
                // the caller claims a web origin but is not a trusted browser
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    private suspend fun create(request: ProviderCreateCredentialRequest): CreatePublicKeyCredentialResponse {
        val call = request.callingRequest as CreatePublicKeyCredentialRequest
        val options = JSONObject(call.requestJson)
        val user = options.getJSONObject("user")
        val exclude = options.optJSONArray("excludeCredentials") ?: JSONArray()
        val (clientData, clientDataHash) =
            clientData(CREATE, options.getString("challenge"), request.callingAppInfo, call.clientDataHash)
        val registration =
            app.authenticator.register(
                clientDataHash = clientDataHash,
                rpId = options.getJSONObject("rp").getString("id"),
                userId = decode(user.getString("id")),
                userName = user.getString("name"),
                displayName = user.optString("displayName"),
                discoverable = true,
                exclude = (0 until exclude.length()).map { decode(exclude.getJSONObject(it).getString("id")) },
            )
        val attestation =
            mapOf(
                "fmt" to "packed",
                "attStmt" to mapOf("alg" to ES256, "sig" to registration.signature),
                "authData" to registration.authData,
            )
        val response =
            JSONObject()
                .put("clientDataJSON", encode(clientData))
                .put("attestationObject", encode(Cbor.encode(attestation)))
                .put("authenticatorData", encode(registration.authData))
                .put("publicKey", encode(SPKI_HEADER + registration.publicKey))
                .put("publicKeyAlgorithm", ES256)
                .put("transports", TRANSPORTS)
        val results = JSONObject().put("credProps", JSONObject().put("rk", true))
        return CreatePublicKeyCredentialResponse(credential(registration.credential.id, response, results))
    }

    private suspend fun get(request: ProviderGetCredentialRequest): GetCredentialResponse {
        val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().first()
        val options = JSONObject(option.requestJson)
        val (clientData, clientDataHash) =
            clientData(GET, options.getString("challenge"), request.callingAppInfo, option.clientDataHash)
        val id = intent.getByteArrayExtra(CREDENTIAL_ID)!!
        val assertion = app.authenticator.assert(options.getString("rpId"), clientDataHash, listOf(id))
        val response =
            JSONObject()
                .put("clientDataJSON", encode(clientData))
                .put("authenticatorData", encode(assertion.authData))
                .put("signature", encode(assertion.signature))
                .put("userHandle", encode(assertion.credential.userId))
        return GetCredentialResponse(PublicKeyCredential(credential(id, response, JSONObject())))
    }

    private fun credential(
        id: ByteArray,
        response: JSONObject,
        results: JSONObject,
    ): String =
        JSONObject()
            .put("id", encode(id))
            .put("rawId", encode(id))
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", response)
            .put("clientExtensionResults", results)
            .toString()

    private fun clientData(
        type: String,
        challenge: String,
        caller: CallingAppInfo,
        browserHash: ByteArray?,
    ): Pair<ByteArray, ByteArray> {
        val data = JSONObject().put("type", type).put("challenge", challenge)
        val browser = caller.isOriginPopulated()
        if (browser) {
            data.put("origin", caller.getOrigin(assets.open("browsers.json").bufferedReader().use { it.readText() }))
        } else {
            val certificate = caller.signingInfo.apkContentsSigners[0].toByteArray()
            data.put("origin", "android:apk-key-hash:" + encode(sha256(certificate)))
            data.put("androidPackageName", caller.packageName)
        }
        val json = data.toString().toByteArray()
        // only a trusted browser may supply its own client data
        return json to (browserHash?.takeIf { browser } ?: sha256(json))
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE)
}
