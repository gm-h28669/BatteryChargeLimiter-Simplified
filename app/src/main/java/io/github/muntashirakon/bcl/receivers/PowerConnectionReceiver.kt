package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment

/**
 * Created by harsha on 30/1/17.
 *
 * This BroadcastReceiver handles the change of the power supply state.
 * Because control files like charging_enabled are causing fake events, there is a time window POWER_CHANGE_TOLERANCE_MS
 * milliseconds where the respective "changes" of the power supply will be ignored.
 *
 * 21/4/17 milux: Changed to avoid service (re)start because of fake power on event
 * 17/4/26 Updated to be dynamically registered by ForegroundService.
 */

class PowerConnectionReceiver(private val service: ForegroundService) : BroadcastReceiver() {
    init {
        Log.d(TAG, "$TAG Created")
    }

    companion object {
        private val TAG = PowerConnectionReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        val isConnected = action == Intent.ACTION_POWER_CONNECTED

        // log events after power change or during state fixing
        if (!Utils.getPrefs(context).getBoolean(PrefsFragment.KEY_IMMEDIATE_POWER_INTENT_HANDLING, false)
            && Utils.isChangePending((BatteryControlReceiver.backOffTime * 2).coerceAtLeast(Constants.POWER_CHANGE_TOLERANCE_MS))) {

            if (isConnected) {
                Log.d(TAG, "Power connected event received while a state transition is in progress.")
            } else {
                Log.d(TAG, "Power disconnected event received while a state transition is in progress.")
            }
        }

        if (isConnected) {
            Log.d(TAG, "Power supply was plugged in.")
        } else {
            Log.d(TAG, "Power supply was unplugged.")
        }

        // Trigger an immediate update of the notification to reflect the new power state
        service.refreshNotification()
    }

    fun detach() {
        Log.d(TAG, "$TAG Receiver detached")
    }
}
