package us.z1x.fidont.transport.provider

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Base64
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import org.json.JSONException
import org.json.JSONObject
import us.z1x.fidont.R
import us.z1x.fidont.app
import us.z1x.fidont.store.Credential

const val CREDENTIAL_ID = "id"

class ProviderService : CredentialProviderService() {
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val entry = CreateEntry(getString(R.string.provider_account), activity(Intent(this, ProviderActivity::class.java)))
        callback.onResult(BeginCreateCredentialResponse(listOf(entry)))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val entries =
            try {
                request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>().flatMap { option ->
                    credentials(JSONObject(option.requestJson)).map { credential ->
                        val intent = Intent(this, ProviderActivity::class.java).putExtra(CREDENTIAL_ID, credential.id)
                        PublicKeyCredentialEntry
                            .Builder(this, credential.userName, activity(intent, credential.id.contentHashCode()), option)
                            .setDisplayName(credential.displayName)
                            .build()
                    }
                }
            } catch (_: JSONException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        callback.onResult(BeginGetCredentialResponse(entries))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) = callback.onResult(null)

    private fun credentials(request: JSONObject): List<Credential> {
        val rpId = request.getString("rpId")
        val allow = request.optJSONArray("allowCredentials")
        if (allow == null || allow.length() == 0) return app.credentials.discoverable(rpId).executeAsList()
        return (0 until allow.length())
            .mapNotNull { index ->
                val id = Base64.decode(allow.getJSONObject(index).getString("id"), Base64.URL_SAFE)
                app.credentials.byId(id).executeAsOneOrNull()
            }.filter { it.rpId == rpId }
    }

    private fun activity(
        intent: Intent,
        requestCode: Int = 0,
    ): PendingIntent = PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
