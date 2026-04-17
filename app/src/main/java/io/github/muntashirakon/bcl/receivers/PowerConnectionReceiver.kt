package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.muntashirakon.bcl.Constants.POWER_CHANGE_TOLERANCE_MS
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
 * 2024: Updated to be dynamically registered by ForegroundService.
 */

class PowerConnectionReceiver(private val service: ForegroundService) : BroadcastReceiver() {
    init {
        Log.d(TAG, "$TAG Created")
    }

    companion object {
        private val TAG = PowerConnectionReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "onReceive: action=$action")

        val isConnected = action == Intent.ACTION_POWER_CONNECTED || action == "${context.packageName}.ACTION_TEST_CONNECTED"
        val isDisconnected = action == Intent.ACTION_POWER_DISCONNECTED || action == "${context.packageName}.ACTION_TEST_DISCONNECTED"

        // ignore new events after power change or during state fixing
        if (!Utils.getPrefs(context).getBoolean(PrefsFragment.KEY_IMMEDIATE_POWER_INTENT_HANDLING, false)
            && Utils.isChangePending((BatteryReceiver.backOffTime * 2).coerceAtLeast(POWER_CHANGE_TOLERANCE_MS))) {

            if (isConnected) {
                // ignore connected event only if service is running
                if (ForegroundService.isRunning
                    || Utils.getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)
                ) {
                    Log.d(TAG, "Charging on transition in progress: ignore event")
                    return
                }
            } else if (isDisconnected) {
                Log.d(TAG, "Charging off transition in progress: ignore event")
                return
            }
        }

        if (isConnected) {
            Log.d(TAG, "Power supply was plugged in. Service is already running.")
        } else if (isDisconnected) {
            Log.d(TAG, "Power supply was unplugged. Stop service")
            Utils.stopService(service, false)
        }
    }

    fun detach(context: Context) {
        Log.d(TAG, "$TAG Receiver detached")
    }
}
