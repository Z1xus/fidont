package us.z1x.fidont.transport.hid

import android.annotation.SuppressLint
import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import us.z1x.fidont.BackgroundBluetoothMode
import us.z1x.fidont.app

class BluetoothCompanion(
    private val context: Context,
) {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)

    fun associate(
        activity: Activity,
        address: String,
        onFailure: () -> Unit,
    ) {
        val device = BluetoothDeviceFilter.Builder().setAddress(address).build()
        val request =
            AssociationRequest
                .Builder()
                .addDeviceFilter(device)
                .setSingleDevice(true)
                .build()
        val callback =
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(sender: IntentSender) {
                    activity.startIntentSender(sender, null, 0, 0, 0)
                }

                override fun onAssociationCreated(association: AssociationInfo) {
                    val previous = context.app.preferences.backgroundBluetooth.value
                    context.app.preferences.setBackgroundBluetoothAssociation(association.id)
                    context.app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Companion)
                    try {
                        observe(association)
                        BluetoothService.handoff(context)
                        context.app.hid.connect(address)
                    } catch (_: RuntimeException) {
                        context.app.preferences.setBackgroundBluetoothAssociation(null)
                        context.app.preferences.setBackgroundBluetooth(previous)
                        manager.disassociate(association.id)
                        onFailure()
                    }
                }

                override fun onFailure(error: CharSequence?) = onFailure()
            }
        manager.associate(request, context.mainExecutor, callback)
    }

    fun restore() {
        if (context.app.preferences.backgroundBluetooth.value != BackgroundBluetoothMode.Companion) return
        val association =
            backgroundAssociation()
                ?: return context.app.preferences.run {
                    setBackgroundBluetoothAssociation(null)
                    setBackgroundBluetooth(BackgroundBluetoothMode.Off)
                }
        try {
            observe(association)
        } catch (_: IllegalStateException) {
            // Presence observation was already restored by Android.
        } catch (_: RuntimeException) {
            context.app.preferences.setBackgroundBluetoothAssociation(null)
            context.app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Off)
        }
    }

    fun disable() {
        val association = backgroundAssociation()
        context.app.preferences.setBackgroundBluetoothAssociation(null)
        if (context.app.preferences.backgroundBluetooth.value == BackgroundBluetoothMode.Companion) {
            context.app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Off)
        }
        if (association != null) {
            try {
                stopObserving(association)
            } catch (_: RuntimeException) {
                // Disassociating below also stops presence observation.
            }
            manager.disassociate(association.id)
        }
    }

    private fun backgroundAssociation(): AssociationInfo? {
        val id = context.app.preferences.backgroundBluetoothAssociation.value ?: return null
        return manager.myAssociations.firstOrNull { it.id == id }
    }

    @Suppress("DEPRECATION")
    private fun observe(association: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 36) {
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build()
            manager.startObservingDevicePresence(request)
        } else {
            manager.startObservingDevicePresence(association.deviceMacAddress!!.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun stopObserving(association: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 36) {
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build()
            manager.stopObservingDevicePresence(request)
        } else {
            manager.stopObservingDevicePresence(association.deviceMacAddress!!.toString())
        }
    }
}

@SuppressLint("NewApi")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BluetoothCompanionService : CompanionDeviceService() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var server: Job? = null

    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared(associationInfo.id)

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared(associationInfo.id)

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED &&
            app.preferences.backgroundBluetoothAssociation.value == event.associationId
        ) {
            app.preferences.setBackgroundBluetoothAssociation(null)
            app.preferences.setBackgroundBluetooth(BackgroundBluetoothMode.Off)
        }
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            -> appeared(event.associationId)

            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED,
            -> disappeared(event.associationId)
        }
    }

    private fun appeared(association: Int) {
        if (app.preferences.backgroundBluetoothAssociation.value == association && server?.isActive != true) {
            server = scope.launch(start = CoroutineStart.UNDISPATCHED) { app.hid.serve() }
            BluetoothService.stop(this)
        }
    }

    private fun disappeared(association: Int) {
        if (app.preferences.backgroundBluetoothAssociation.value == association) {
            server?.cancel()
            server = null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
