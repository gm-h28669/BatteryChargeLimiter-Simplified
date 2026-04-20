package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment

/**
 * Receiver for battery events when limit control is disabled.
 * Simply logs the event and updates the notification with battery info.
 */
class BatteryMonitorReceiver(private val service: ForegroundService) : BroadcastReceiver() {
    private val prefs = Utils.getPrefs(service.baseContext)
    private var useFahrenheit = prefs.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        Log.d(TAG, "Created")
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == PrefsFragment.KEY_TEMP_FAHRENHEIT) {
                this.useFahrenheit = sharedPreferences.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
                val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    updateNotification(batteryIntent)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            val batteryLevel = Utils.getBatteryLevel(intent)
            val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val isActuallyCharging = Utils.isActuallyCharging(service, Constants.CURRENT_THRESHOLD_MA)

            val chargingStr = if (isActuallyCharging) "CHARGING" else "DISCHARGING"
            val batteryCurrentAvgMilliAmps = Utils.getBatteryCurrentAvgInMilliAmps(context)
            val batteryCurrentAvgStr = Utils.getIntegerOrNotAvailable(batteryCurrentAvgMilliAmps)
            val chargerCurrentNowInMilliAmps = Utils.getChargerCurrentNowInMilliAmps()
            val chargerCurrentNowStr = Utils.getIntegerOrNotAvailable(chargerCurrentNowInMilliAmps)

            Log.d(TAG, "[Monitor: $chargingStr]  [Battery: $batteryLevel% ${Utils.getBatteryStatusText(batteryStatus)} ${batteryCurrentAvgStr}mA ${Utils.getPowerSource(context)}] [Charger: ${chargerCurrentNowStr}mA]")

            updateNotification(intent)
        }
    }

    private fun updateNotification(intent: Intent) {
        service.setNotificationTitle(service.getString(R.string.monitoring_battery))
        service.setNotificationIcon(Constants.NOTIF_MONITOR)
        service.setNotificationActionText(service.getString(R.string.dismiss))

        Utils.getBatteryInfoAsync(service, intent, useFahrenheit) { info ->
            val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val isActuallyCharging = Utils.isActuallyCharging(service, Constants.CURRENT_THRESHOLD_MA)
            val status = Utils.getBatteryStatusTextLocalized(service, batteryStatus, isActuallyCharging)
            
            service.setNotificationContentText("$status\n$info")
            service.updateNotification()
        }
    }

    fun detach() {
        Log.d(TAG, "Detached")
        prefs.unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        this.preferenceChangeListener = null
    }

    companion object {
        private val TAG = BatteryMonitorReceiver::class.java.simpleName
    }
}
