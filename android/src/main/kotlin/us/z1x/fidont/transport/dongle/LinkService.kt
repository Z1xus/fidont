package us.z1x.fidont.transport.dongle

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import us.z1x.fidont.app
import kotlin.time.Duration.Companion.seconds

// a site often sends a second request right after the first
private val IDLE = 30.seconds

class LinkService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch { app.dongle.serve() }
        scope.launch {
            app.dongle.pending.collectLatest {
                if (it == 0) {
                    delay(IDLE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(REQUEST_NOTIFICATION, requestNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
