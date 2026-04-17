package io.github.muntashirakon.bcl.receivers

import android.content.*
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.muntashirakon.bcl.ChargeMode
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MAX_BACK_OFF_TIME
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
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
    private var lastPluggedIn: Boolean? = null
    private var limitPercentage: Int = 0
    private var rechargePercentage: Int = 0
    private val prefs = Utils.getPrefs(service.baseContext)
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val settings = service.getSharedPreferences(SETTINGS, 0)
    private var useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)
    private var hideToastOnServiceChanges = prefs.getBoolean(PrefsFragment.KEY_HIDE_TOAST_ON_SERVICE_CHANGES, false)

    init {
        Log.d(TAG, "$TAG Created")
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
                PrefsFragment.KEY_HIDE_TOAST_ON_SERVICE_CHANGES -> {
                    this.hideToastOnServiceChanges = prefs.getBoolean(PrefsFragment.KEY_HIDE_TOAST_ON_SERVICE_CHANGES, false)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        settings.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        this.useFahrenheit = prefs.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
        this.hideToastOnServiceChanges = prefs.getBoolean(PrefsFragment.KEY_HIDE_TOAST_ON_SERVICE_CHANGES, false)
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
            Log.d(TAG, "State changed from $lastState to $newState")
            lastState = newState
        }
        return stateHasChanged
    }

    // executed on state transition: INITIAL -> INITIAL_CHARGING
    private fun handleInitialCharging(batteryLevel: Int) {
        Log.d(TAG, "Started initial charging. New State: ${ReceiverState.INITIAL_CHARGING} Level=$batteryLevel")
        Utils.changeState(service, ChargeMode.ON)
        service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
        service.setNotificationIcon(NOTIF_CHARGE)
        service.setNotificationActionText(service.getString(R.string.disable_temporarily))
        backOffTime = CHARGING_CHANGE_TOLERANCE_MS
    }

    // executed on state transitions:
    // INITIAL -> STOPPED_AT_LIMIT
    // INITIAL_CHARGING -> STOPPED_AT_LIMIT
    // MAINTENANCE_CHARGING -> STOPPED_AT_LIMIT
    private fun handleReachedLimit(batteryLevel: Int, isInitialRun: Boolean) {
        Log.d(TAG, "Initial charging completed. New State: ${ReceiverState.STOPPED_AT_LIMIT} Level=$batteryLevel")

        // play sound when the limit was reached
        if (useNotificationSound && !isInitialRun) {
            service.setNotificationSound()
        }
        // remember that we let the device charge until limit at least once
        chargedToLimit = true
        // active auto reset on service shutdown
        service.enableAutoReset()
        Utils.changeState(service, ChargeMode.OFF)

        // set the "maintain" notification, this must not change from now
        service.setNotificationTitle(
            service.getString(R.string.maintaining_x_to_y, rechargePercentage, limitPercentage)
        )
        service.setNotificationIcon(NOTIF_MAINTAIN)
        service.setNotificationActionText(service.getString(R.string.dismiss))
    }

    // executed on state transition: STOPPED_AT_LIMIT -> MAINTENANCE_CHARGING
    private fun handleMaintenanceCharging(batteryLevel: Int) {
        Log.d(TAG, "Starting maintenance charging. New State: $lastState Level=$batteryLevel")
        service.setNotificationIcon(NOTIF_CHARGE)
        service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
        service.setNotificationActionText(service.getString(R.string.disable_temporarily))
        Utils.changeState(service, ChargeMode.ON)
        backOffTime = CHARGING_CHANGE_TOLERANCE_MS
    }


    private fun handleStoppedAtLimitBehavior(batteryLevel: Int, batteryStatus: Int) {
        // use both system status and actual current to determine if we are still charging
        val isActuallyCharging = Utils.isActuallyCharging(service, batteryStatus)

        if (isActuallyCharging
            && prefs.getBoolean(PrefsFragment.KEY_ENFORCE_CHARGE_LIMIT, true)) {

            if (batteryLevel <= limitPercentage + 1) {
                // level in range [limit, limit+1]
                // Attempting to "pulse" the hardware to stop charging
                // Double the back off time with every unsuccessful round up to MAX_BACK_OFF_TIME
                backOffTime = (backOffTime * 2).coerceAtMost(MAX_BACK_OFF_TIME)
                Log.d(TAG, "Still charging and level is at or slightly above the limit. Pulse charge on/off: State=${lastState} Level=$batteryLevel Delay: $backOffTime")

                // the device did not stop charging, try to "cycle" the state to fix this
                Utils.changeState(service, ChargeMode.ON)
                // schedule the charging stop command to be executed after backOffTime
                val service = this.service
                handler.postDelayed({ Utils.changeState(service, ChargeMode.OFF) }, backOffTime)
            } else {
                // level in range [limit+1, 100]
                // we are significantly above the limit: don't "cycle" (pulse ON) the state. Just try to force it OFF again
                // silently. This prevents the "Pulse to 1" bug when plugging in while already above the limit+1.
                Log.d(TAG, "Still charging and level has drifted further past the limit. Stop charging: State=${lastState} Level=$batteryLevel")
                Utils.changeState(service, ChargeMode.OFF)
                backOffTime = CHARGING_CHANGE_TOLERANCE_MS
            }
        } else {
            // nothing to do, since:
            if (prefs.getBoolean(PrefsFragment.KEY_ENFORCE_CHARGE_LIMIT, true)) {
                // charging stopped on hitting limit or power supply unplugged
                Log.d(TAG,"Discharging after charging to limit or power supply unplugged. State=${lastState} Level=$batteryLevel")
            } else {
                // option "Force to limit" is disabled
                Log.d(TAG,"Option 'Force to limit' is disabled. State=${lastState} Level=$batteryLevel")
            }

            backOffTime = CHARGING_CHANGE_TOLERANCE_MS
        }
    }



    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        // ignore events while trying to fix charging state, see below
        if (Utils.isChangePending(backOffTime * 2)) {
            return
        }

        val pluggedIn = Utils.isDevicePluggedIn(context)
        if (useNotificationSound && lastPluggedIn != null && lastPluggedIn != pluggedIn) {
            service.setNotificationSound()
        }
        lastPluggedIn = pluggedIn
        val isInitialRun = lastState == ReceiverState.INITIAL

        // log battery and charger info
        val batteryLevel = Utils.getBatteryLevel(intent)
        val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val batteryCurrentAvgMilliAmps = Utils.getBatteryCurrentAvgInMilliAmps(context)
        val batteryCurrentAvgStr = Utils.getIntegerOrNotAvailable(batteryCurrentAvgMilliAmps)
        val chargerCurrentNowInMilliAmps = Utils.getChargerCurrentNowInMilliAmps()
        val chargerCurrentNowStr = Utils.getIntegerOrNotAvailable(chargerCurrentNowInMilliAmps)

        // This measurement is not helpful, since it is not what has been negotiated between device and power source.
        // It is simply a configured constant value in driver. Each power source (AC, USB, etc.) has its own configured
        // value. In reality current_now may be much higher than current_max, depending on power supply.
        //val chargerCurrentMaxInMilliAmps = Utils.getChargerCurrentMaxInMilliAmps(context)
        //val chargerCurrentMaxStr = Utils.getIntegerOrNotAvailable(chargerCurrentMaxInMilliAmps)

        Log.d(TAG, "$lastState [Battery: $batteryLevel% ${Utils.getBatteryStatusText(batteryStatus)} ${batteryCurrentAvgStr}mA ${Utils.getPowerSource(context)}] [Charger: ${chargerCurrentNowStr}mA")

        if (prefs.getBoolean(PrefsFragment.KEY_TEMP_IN_NOTIF, true)) {
            Utils.getBatteryInfoAsync(service, intent, useFahrenheit) { info ->
                service.setNotificationContentText(info)
                service.updateNotification()
            }
        } else {
            service.setNotificationContentText(service.getString(R.string.waiting_description))
        }

        when (lastState) {
            ReceiverState.INITIAL -> {
                if (batteryLevel < limitPercentage) {
                    if (switchState(ReceiverState.INITIAL_CHARGING)) {
                        handleInitialCharging(batteryLevel)
                    }
                } else if (switchState(ReceiverState.STOPPED_AT_LIMIT)) {
                    handleReachedLimit(batteryLevel, isInitialRun)
                }
            }

            ReceiverState.INITIAL_CHARGING -> {
                if (batteryLevel >= limitPercentage) {
                    if (switchState(ReceiverState.STOPPED_AT_LIMIT)) {
                        handleReachedLimit(batteryLevel, isInitialRun)
                    }
                }
            }

            ReceiverState.STOPPED_AT_LIMIT -> {
                if (batteryLevel < rechargePercentage) {
                    if (switchState(ReceiverState.MAINTENANCE_CHARGING)) {
                        handleMaintenanceCharging(batteryLevel)
                    }
                } else if (batteryLevel >= limitPercentage) {
                    handleStoppedAtLimitBehavior(batteryLevel, batteryStatus)
                }
            }

            ReceiverState.MAINTENANCE_CHARGING -> {
                if (batteryLevel >= limitPercentage) {
                    if (switchState(ReceiverState.STOPPED_AT_LIMIT)) {
                        handleReachedLimit(batteryLevel, isInitialRun)
                    }
                }
            }
        }

        // update battery status information and rebuild notification
        service.updateNotification()
    }

    fun detach(context: Context) {
        Log.d(TAG, "$TAG Receiver detached")
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
