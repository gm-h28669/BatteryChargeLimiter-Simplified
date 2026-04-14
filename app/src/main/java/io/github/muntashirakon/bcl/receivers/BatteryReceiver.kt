package io.github.muntashirakon.bcl.receivers

import android.content.*
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import io.github.muntashirakon.bcl.ChargeMode
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MAX_BACK_OFF_TIME
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.POWER_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment

/**
 * Created by Michael on 01.04.2017.
 *
 * Dynamically created receiver for battery events. Only registered if power supply is attached.
 */
class BatteryReceiver(private val service: ForegroundService) : BroadcastReceiver() {

    enum class ReceiverState {
        INITIAL,
        INITIAL_CHARGING,
        STOPPED_AT_LIMIT,
        MAINTENANCE_CHARGING
    }

    private var chargedToLimit = false
    private var useFahrenheit = false
    private var lastState = ReceiverState.INITIAL
    private var limitPercentage: Int = 0
    private var rechargePercentage: Int = 0
    private val prefs = Utils.getPrefs(service.baseContext)
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val settings = service.getSharedPreferences(SETTINGS, 0)
    private var useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)

    init {
        Log.d(TAG, "${TAG} Started")
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                PrefsFragment.KEY_TEMP_FAHRENHEIT -> {
                    useFahrenheit = sharedPreferences.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
                    val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (batteryIntent != null) {
                        Utils.getBatteryInfoAsync(service, batteryIntent, useFahrenheit) { info ->
                            service.setNotificationContentText(info)
                            service.updateNotification()
                        }
                    }
                }
                LIMIT, MIN -> {
                    reset(sharedPreferences)
                }
                PrefsFragment.KEY_NOTIFICATION_SOUND -> {
                    this.useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        settings.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        this.useFahrenheit = prefs.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
        reset(settings)
    }

    private fun reset(settings: SharedPreferences) {
        Log.d(TAG, "Reset to initial values")
        chargedToLimit = false
        lastState = ReceiverState.INITIAL
        backOffTime = CHARGING_CHANGE_TOLERANCE_MS
        limitPercentage = settings.getInt(LIMIT, Constants.DEFAULT_LIMIT_PC)
        rechargePercentage = settings.getInt(MIN, Constants.DEFAULT_MIN_PC)
        // manually fire onReceive() to update state if service is enabled
        onReceive(service, service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!)
    }

    /**
     * Remembers the new state and returns whether the state was changed
     *
     * @param newState the new state
     * @return whether the state has changed
     */
    private fun switchState(newState: ReceiverState): Boolean {
        val stateHasChanged = lastState != newState
        if (stateHasChanged) {
            Log.d(TAG, "State changed from ${lastState.name} to ${newState.name}")
            lastState = newState
        }
        return stateHasChanged
    }

    /**
     * If battery should be charging, but there's no power supply, stop the service.
     * NOT to be called if charging is expected to be disabled!
     */
    private fun stopIfUnplugged() {
        // save the state that caused this function call
        val triggerState = lastState
        handler.postDelayed({
            // continue only if the state didn't change in the meantime
            if (triggerState == lastState && !Utils.isDevicePluggedIn(service)) {
                Log.d(TAG, "Stopping service, since power supply not plugged in")
                Utils.stopService(service, false)
            }
        }, POWER_CHANGE_TOLERANCE_MS)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ignore events while trying to fix charging state, see below
        if (Utils.isChangePending(backOffTime * 2)) {
            return
        }

        val batteryLevel = Utils.getBatteryLevel(intent)
        val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pluggedIn = if (Utils.isDevicePluggedIn(context)) { "Yes" } else { "No"}
        Log.d(TAG, "State: $lastState.name Battery: Level=$batteryLevel Status=${Utils.getBatteryStatusText(batteryStatus)} PluggedIn=$pluggedIn Source=${Utils.getPowerSource(context)}")

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val showBatteryInfoInNotif = preferences.getBoolean("temp_in_notif", false)

        if (showBatteryInfoInNotif) {
            Utils.getBatteryInfoAsync(service, intent, useFahrenheit) { info ->
                service.setNotificationContentText(info)
                service.updateNotification()
            }
        } else {
            service.setNotificationContentText(service.getString(R.string.waiting_description))
        }
        // when the service was "freshly started", charge until limit
        if (!chargedToLimit && batteryLevel < limitPercentage) {
            if (switchState(ReceiverState.INITIAL_CHARGING)) {
                Log.d(TAG, "Started initial charging. New State: ${ReceiverState.INITIAL_CHARGING.name} Level=$batteryLevel")
                Utils.changeState(service, ChargeMode.ON)
                service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
                service.setNotificationIcon(NOTIF_CHARGE)
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                stopIfUnplugged()
            }
        } else if (batteryLevel >= limitPercentage) {
            if (switchState(ReceiverState.STOPPED_AT_LIMIT)) {
                Log.d(TAG, "Initial charging completed. New State: ${ReceiverState.STOPPED_AT_LIMIT.name} Level=$batteryLevel")

                // play sound only the first time when the limit was reached
                if (useNotificationSound && !chargedToLimit) {
                    service.setNotificationSound()
                }
                // remember that we let the device charge until limit at least once
                chargedToLimit = true
                // active auto reset on service shutdown
                service.enableAutoReset()
                Utils.changeState(service, ChargeMode.OFF)

                if (preferences.getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
                    Utils.stopService(service, false)
                }

                // set the "maintain" notification, this must not change from now
                service.setNotificationTitle(
                    service.getString(R.string.maintaining_x_to_y, rechargePercentage, limitPercentage)
                )
                service.setNotificationIcon(NOTIF_MAINTAIN)
                service.setNotificationActionText(service.getString(R.string.dismiss))
            } else if (batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                && prefs.getBoolean(PrefsFragment.KEY_ENFORCE_CHARGE_LIMIT, true)) {

                // If we are slightly above the limit, don't "cycle" (pulse ON) the state. Just try to force it OFF again
                // silently. This prevents the "Pulse to 1" bug when plugging in while already above the limit.
                if (batteryLevel > limitPercentage + 1) {
                    Log.d(TAG, "Charging and slightly above upper limit. Stop charging: State=${lastState.name} Level=$batteryLevel")
                    Utils.changeState(service, ChargeMode.OFF)
                    backOffTime = CHARGING_CHANGE_TOLERANCE_MS
                    return
                }

                // Double the back off time with every unsuccessful round up to MAX_BACK_OFF_TIME
                backOffTime = (backOffTime * 2).coerceAtMost(MAX_BACK_OFF_TIME)
                Log.d(TAG, "Currently charging and significantly above upper limit. Pulse charge on/off: State=${lastState.name} Level=$batteryLevel Delay: $backOffTime")

                // if the device did not stop charging, try to "cycle" the state to fix this
                Utils.changeState(service, ChargeMode.ON)
                // schedule the charging stop command to be executed after CHARGING_CHANGE_TOLERANCE_MS
                val service = this.service
                handler.postDelayed({ Utils.changeState(service, ChargeMode.OFF) }, backOffTime)
            } else {
                backOffTime = CHARGING_CHANGE_TOLERANCE_MS
            }
        } else if (batteryLevel < rechargePercentage) {
            if (switchState(ReceiverState.MAINTENANCE_CHARGING)) {
                Log.d(TAG, "Staring maintenance charging. New State: ${lastState.name} Level=$batteryLevel")
                service.setNotificationIcon(NOTIF_CHARGE)
                service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                Utils.changeState(service, ChargeMode.ON)
                stopIfUnplugged()
            }
        }

        // update battery status information and rebuild notification
        // service.setNotificationContentText(Utils.getBatteryInfo(service, intent, useFahrenheit))
        service.updateNotification()
    }

    fun detach(context: Context) {
        Log.d(TAG, "${TAG} Receiver detached")
        // unregister the listener that listens for relevant change events
        prefs.unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        Utils.getSettings(context)
            .unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        // technically not necessary, but it prevents inlining of this required field
        // see end of https://developer.android.com/guide/topics/ui/settings.html#Listening
        this.preferenceChangeListener = null
    }

    companion object {
        private val TAG = BatteryReceiver::class.java.simpleName
        private val handler = Handler(Looper.getMainLooper())
        internal var backOffTime = CHARGING_CHANGE_TOLERANCE_MS
    }
}
