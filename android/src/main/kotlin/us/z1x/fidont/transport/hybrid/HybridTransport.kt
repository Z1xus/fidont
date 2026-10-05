package us.z1x.fidont.transport.hybrid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import us.z1x.fidont.app
import us.z1x.fidont.hybrid.Hybrid
import us.z1x.fidont.hybrid.Qr
import us.z1x.fidont.hybrid.Tunnel
import kotlin.time.Duration.Companion.seconds

private val SERVICE = ParcelUuid.fromString("0000fff9-0000-1000-8000-00805f9b34fb")
private val TIMEOUT = 30.seconds

class HybridTransport(
    private val context: Context,
) {
    private val client = OkHttpClient()

    @SuppressLint("MissingPermission")
    suspend fun serve(qr: Qr): Boolean {
        val advertiser = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeAdvertiser ?: return false
        val callback = object : AdvertiseCallback() {}
        var socket: WebSocket? = null
        try {
            return Hybrid(context.app.authenticator).serve(
                qr,
                context.app.preferences.relay.value,
                connect = { url -> connect(url) { socket = it } },
                advertise = { advert ->
                    val settings =
                        AdvertiseSettings
                            .Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(false)
                            .build()
                    val data =
                        AdvertiseData
                            .Builder()
                            .addServiceUuid(SERVICE)
                            .addServiceData(SERVICE, advert)
                            .build()
                    advertiser.startAdvertising(settings, data, callback)
                },
            )
        } finally {
            advertiser.stopAdvertising(callback)
            socket?.cancel()
        }
    }

    private suspend fun connect(
        url: String,
        opened: (WebSocket) -> Unit,
    ): Tunnel? {
        val routing = Channel<ByteArray?>(1)
        val messages = Channel<ByteArray>(Channel.UNLIMITED)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    routing.trySend(response.header("X-caBLE-Routing-ID")?.hexToByteArray())
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    messages.trySend(bytes.toByteArray())
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    messages.close()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    routing.trySend(null)
                    messages.close()
                }
            }
        val request =
            Request
                .Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "fido.cable")
                .build()
        val socket = client.newWebSocket(request, listener).also(opened)
        val routingId = withTimeoutOrNull(TIMEOUT) { routing.receive() } ?: return null
        return object : Tunnel {
            override val routingId = routingId

            override suspend fun send(message: ByteArray) {
                socket.send(message.toByteString())
            }

            override suspend fun receive(): ByteArray? = withTimeoutOrNull(TIMEOUT) { messages.receiveCatching().getOrNull() }
        }
    }
}
