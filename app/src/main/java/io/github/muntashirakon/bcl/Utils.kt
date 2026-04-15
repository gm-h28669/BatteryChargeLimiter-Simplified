package io.github.muntashirakon.bcl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.google.android.material.internal.ViewUtils.doOnApplyWindowInsets
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.settings.PrefsFragment
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


enum class ChargeMode {
    INITIAL,
    ON,
    OFF
}

object Utils {
    private val TAG = Utils::class.java.simpleName
    private const val NOT_AVAILABLE = "---"

    // remember pending state change
    private var changePending: Long = 0

    // remember initialization
    private var cfInitialized: Boolean = false

    /**
     * Inform the BatteryReceiver instance(es) to ignore events for CHARGING_CHANGE_TOLERANCE_MS,
     * in order to let the state change settle.
     */
    private fun setChangePending() {
        // update changePending to prevent concurrent state changes before execution
        changePending = System.currentTimeMillis()
    }

    /**
     * Returns whether some change happened at most CHARGING_CHANGE_TOLERANCE_MS ago.
     *
     * @return Whether state change is pending
     */
    fun isChangePending(tolerance: Long): Boolean {
        return System.currentTimeMillis() <= changePending + tolerance
    }

    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun changeState(context: Context, chargeMode: ChargeMode) {
        if (chargeMode == ChargeMode.INITIAL) return // Should not happen in changeState

        if (!isCtrlFileSet(context)) {
            Log.e(TAG, "Attempted to change state without control file set")
            return
        }

        val preferences = getPrefs(context)
        val alwaysWrite = preferences.getBoolean(PrefsFragment.KEY_ALWAYS_WRITE_CF, false)

        val file = getCtrlFileData(context)
        val newState = if (chargeMode == ChargeMode.ON) {
            getCtrlEnabledData(context)
        } else {
            getCtrlDisabledData(context)
        }

        val switchCommands: Array<String>
        if (cfInitialized) {
            switchCommands = arrayOf("echo \"$newState\" > $file")
        } else {
            cfInitialized = true
            switchCommands = arrayOf(
                "if [ ! -w $file ]; then mount -o rw,remount $file 2>/dev/null; chmod u+w $file; fi",
                "echo \"$newState\" > $file"
            )
        }

        // Set cooldown to ignore stale battery intents while the hardware and kernel transition states
        setChangePending()

        if (alwaysWrite) {
            Shell.cmd(switchCommands.joinToString(separator = " && ")).submit {
                if (it.isSuccess) {
                    Log.d(TAG, "Set value in $file to $newState")
                }
                else {
                    Log.e(TAG, "Failed to write value $newState to $file (exit=${it.code}). Hint: If running on emulator, use: adb shell dumpsys battery set status ...")
                }
            }
        } else {
            Shell.cmd("cat $file").submit {
                if (it.isSuccess) {
                    Log.d(TAG, "Read value in $file")
                    // Only compare and write if the read was successful
                    if (it.out.isEmpty() || it.out[0] != newState) {
                        Shell.cmd(switchCommands.joinToString(separator = " && ")).submit { result ->
                            if (result.isSuccess) {
                                Log.d(TAG, "Set value in $file to $newState")
                            }
                            else {
                                Log.e(TAG, "Failed to write value $newState to $file (exit=${result.code}). Hint: If running on emulator, use: adb shell dumpsys battery set status ...")
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to read value from $file (exit=${it.code}). Hint: If running on emulator: file may not exist")
                }
            }
        }
    }

    private var ctrlFiles: List<ControlFile>? = null
    fun getCtrlFiles(context: Context): List<ControlFile> {
        if (ctrlFiles == null) {
            try {
                val r = InputStreamReader(
                    context.resources.openRawResource(R.raw.control_files),
                    Charset.forName("UTF-8")
                )
                val type = object : TypeToken<List<ControlFile>>() {}.type
                ctrlFiles = Gson().fromJson<List<ControlFile>>(r, type)!!.sortedWith(compareBy(
                    { it.order }, { it.issues }, { it.experimental }, { it.file })
                )
            } catch (e: Exception) {
                Log.wtf(context.javaClass.simpleName, e)
                return emptyList()
            }
        }
        return ctrlFiles!!
    }

    @WorkerThread
    fun validateCtrlFiles(context: Context) {
        for (cf in getCtrlFiles(context)) {
            cf.validate()
        }
    }

    fun setCtrlFile(context: Context, cf: ControlFile) {
        //This will immediately reset the current control file
        stopService(context)
        getPrefs(context)
            .edit().putString(PrefsFragment.KEY_CONTROL_FILE, cf.file).apply()
        getSettings(context)
            .edit().putString(Constants.FILE_KEY, cf.file)
            .putString(Constants.CHARGE_ON_KEY, cf.chargeOn)
            .putString(Constants.CHARGE_OFF_KEY, cf.chargeOff).apply()
        //Respawn the service if necessary
        startServiceIfLimitEnabled(context)
    }

    fun getPowerSource(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Unplugged"
            else -> "Unknown ($plugged)"
        }
    }

    fun isDevicePluggedIn(context: Context): Boolean {
        val batteryIntent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )!!
        return (batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                == BatteryManager.BATTERY_STATUS_CHARGING
                || batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) > 0)
    }

    fun getBatteryLevel(batteryIntent: Intent): Int {
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        return if (level == -1 || scale == -1) {
            50
        } else {
            level * 100 / scale
        }
    }

    // returns averaged current in uA
    // for current measurement the files seem to be more reliable and more likely to contain correct readings (in uA)
    // therefore prioritize files (if they exist) and try to query BatteryManager only if file based approach fails
    fun getAverageCurrent(context: Context): Int {
        val currentAvgFiles = arrayOf(
            "/sys/class/power_supply/battery/batt_current_ua_avg",
            "/sys/class/power_supply/battery/current_avg"
        )

        var rawAverageCurrent = Int.MIN_VALUE
        for (path in currentAvgFiles) {
            val result = Shell.cmd("if [ -f \"$path\" ]; then cat \"$path\"; fi").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                val line = result.out[0].trim()
                if (line.isNotEmpty()) {
                    val value = line.toIntOrNull()
                    if (value != null) {
                        rawAverageCurrent = value
                        break
                    }
                }
            }
        }
        if (rawAverageCurrent == Int.MIN_VALUE) {
            // fallback
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            rawAverageCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        }
        return rawAverageCurrent
    }

    fun getBatteryInfo(context: Context, intent: Intent, useFahrenheit: Boolean): String {
        val batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val rawAverageCurrent = getAverageCurrent(context)
        val powerSource = getPowerSource(context)
        val voltageStr = if (batteryVoltage != -1) String.format(Locale.ROOT, "%.3f", batteryVoltage.toFloat() / 1000f) else NOT_AVAILABLE
        val currentStr = if (rawAverageCurrent != Int.MIN_VALUE && rawAverageCurrent != 0) (rawAverageCurrent / 1000).toString() else NOT_AVAILABLE
        val temperatureStr = if (batteryTemperature != -1) {
            val temp = if (useFahrenheit) 32f + batteryTemperature * 1.8f / 10f else batteryTemperature / 10f
            String.format(Locale.ROOT, "%.1f", temp)
        } else NOT_AVAILABLE

        return context.getString(
            if (useFahrenheit) R.string.battery_info_F else R.string.battery_info_C,
            voltageStr,
            currentStr,
            temperatureStr,
            powerSource
        )
    }

     // Asynchronously fetches battery info and returns it via a listener on the Main Thread.
    fun getBatteryInfoAsync(context: Context, intent: Intent, useFahrenheit: Boolean, listener: (String) -> Unit) {
        executor.execute {
            val info = getBatteryInfo(context, intent, useFahrenheit)
            Handler(Looper.getMainLooper()).post {
                listener(info)
            }
        }
    }

    //    @SuppressLint("PrivateApi")
    fun resetBatteryStats(context: Context) {
        Shell.cmd("dumpsys batterystats --reset").submit {
            if (it.isSuccess) {
                Log.d(TAG, "Reset battery statistics")
                Toast.makeText(context, R.string.stats_reset_success, Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Reset of battery statistics failed")
            }
        }
    }

    fun setLimit(limit: Int, settings: SharedPreferences) {
        val min = settings.getInt(Constants.MIN, Constants.DEFAULT_MIN_PC)
        val edit = settings.edit().putInt(Constants.LIMIT, limit)
        if (min >= limit) {
            edit.putInt(Constants.MIN, (limit - 1).coerceAtLeast(0))
        }
        edit.apply()
    }

    fun handleLimitChange(context: Context, newLimit: Any?) {
        Log.d(TAG, "User has changed limit: $newLimit")
        try {
            if (newLimit == null) {
                throw NumberFormatException("null")
            }
            val limit = if (newLimit is Number) {
                newLimit.toInt()
            } else {
                Integer.parseInt(newLimit.toString())
            }
            if (limit == Constants.MAX_ALLOWED_LIMIT_PC) {
                val settings = getSettings(context)
                stopService(context)
                settings.edit().putBoolean(Constants.CHARGE_LIMIT_ENABLED, false).apply()
            } else if (limit in Constants.MIN_ALLOWED_LIMIT_PC until Constants.MAX_ALLOWED_LIMIT_PC) {
                val settings = getSettings(context)
                // set the new limit
                setLimit(limit, settings)
                Toast.makeText(
                    context, context.getString(R.string.intent_limit_accepted, limit),
                    Toast.LENGTH_SHORT
                ).show()
                if (!settings.getBoolean(Constants.NOTIFICATION_LIVE, false)) {
                    if (isCtrlFileSet(context)) {
                        settings.edit().putBoolean(Constants.CHARGE_LIMIT_ENABLED, true).apply()
                        startServiceIfLimitEnabled(context)
                    } else {
                        Toast.makeText(context, R.string.file_data, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                throw NumberFormatException("Battery limit out of range!")
            }
        } catch (fe: NumberFormatException) {
            Toast.makeText(context, R.string.intent_limit_invalid, Toast.LENGTH_SHORT).show()
        }

    }

    fun startServiceIfLimitEnabled(context: Context) {
        val settings = getSettings(context)
        if (!settings.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)) {
            return
        }

        if (!isCtrlFileSet(context)) {
            settings.edit().putBoolean(Constants.CHARGE_LIMIT_ENABLED, false).apply()
            EnableWidget.updateWidget(context, false)
            return
        }

        // Proactively assess the state and set a cooldown timer to avoid race conditions
        // with the transitional "Charging" status reported by the system on plug-in.
        val batteryIntent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = getBatteryLevel(batteryIntent)
            val limit = settings.getInt(Constants.LIMIT, 80)
            if (level < limit) {
                Log.d(TAG, "Start charging, since target not reached: Level=$level Target=$limit")
                changeState(context, ChargeMode.ON)
            } else {
                // If we are already over the limit, force the OFF state immediately.
                // This calls setChangePending(), which tells BatteryReceiver to ignore
                // the initial (and possibly "Charging") intent from the system.
                Log.d(TAG, "Stop charging, since target reached or passed: Level=$level Target=$limit")
                changeState(context, ChargeMode.OFF)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            ContextCompat.startForegroundService(context, Intent(context, ForegroundService::class.java))
            // display service enabled Toast message if not disabled in settings
            if (!getPrefs(context).getBoolean("hide_toast_on_service_changes", false)) {
                Toast.makeText(context, R.string.service_enabled, Toast.LENGTH_SHORT).show()
            }
        }, Constants.CHARGING_CHANGE_TOLERANCE_MS)
    }

    fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun getSettings(context: Context): SharedPreferences {
        return context.getSharedPreferences(Constants.SETTINGS, 0)
    }

    fun stopService(context: Context, ignoreAutoReset: Boolean = true) {
        Log.d(TAG, "Stopping service")
        val wasServiceRunning = ForegroundService.isRunning
        if (ignoreAutoReset) {
            ForegroundService.ignoreAutoReset()
        }
        context.stopService(Intent(context, ForegroundService::class.java))
        if (!getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
            changeState(context, ChargeMode.ON)
        }
        // display service disabled Toast message if not disabled in settings
        if (wasServiceRunning && !getPrefs(context).getBoolean("hide_toast_on_service_changes", false)) {
            Toast.makeText(context, R.string.service_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    fun isCtrlFileSet(context: Context): Boolean {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean(PrefsFragment.KEY_CUSTOM_CTRL_FILE_DATA, false)) {
            settings.contains(Constants.SAVED_PATH_DATA)
        } else {
            settings.contains(Constants.FILE_KEY)
        }
    }

    fun getCtrlFileData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean(PrefsFragment.KEY_CUSTOM_CTRL_FILE_DATA, false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_PATH_DATA, Constants.DEFAULT_FILE)!!
        } else {
            // Custom Data Disabled
            settings.getString(Constants.FILE_KEY, Constants.DEFAULT_FILE)!!
        }
    }

    fun getCtrlEnabledData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean(PrefsFragment.KEY_CUSTOM_CTRL_FILE_DATA, false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_ENABLED_DATA, Constants.DEFAULT_ENABLED)!!
        } else {
            // Custom Data Disabled
            settings.getString(Constants.CHARGE_ON_KEY, Constants.DEFAULT_ENABLED)!!
        }
    }

    fun getCtrlDisabledData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean(PrefsFragment.KEY_CUSTOM_CTRL_FILE_DATA, false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_DISABLED_DATA, Constants.DEFAULT_DISABLED)!!
        } else {
            // Custom Data Disabled
            settings.getString(Constants.CHARGE_OFF_KEY, Constants.DEFAULT_DISABLED)!!
        }
    }

    fun setTheme(activity: Activity, splashScreen: Boolean = false) {
        val preferences = getPrefs(activity)
        val getTheme = preferences.getString(PrefsFragment.KEY_THEME, Constants.LIGHT)
        var theme = if (splashScreen) R.style.AppTheme_Splash else R.style.AppTheme
        var nightMode = AppCompatDelegate.MODE_NIGHT_NO
        when (getTheme) {
            Constants.LIGHT -> {
                nightMode = AppCompatDelegate.MODE_NIGHT_NO
            }
            Constants.DARK -> {
                nightMode = AppCompatDelegate.MODE_NIGHT_YES
            }
            Constants.BLACK -> {
                nightMode = AppCompatDelegate.MODE_NIGHT_YES
                theme = if (splashScreen) R.style.AppTheme_Splash_Black else R.style.AppTheme_Black
            }
        }
        activity.setTheme(theme)
        AppCompatDelegate.setDefaultNightMode(nightMode)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }

    // Copied from App Manager
    @SuppressLint("RestrictedApi")
    @Suppress("DEPRECATION")
    fun applyWindowInsetsAsPaddingNoTop(v: View) {
        doOnApplyWindowInsets(v) { view, insets, initialPadding ->
            if (!ViewCompat.getFitsSystemWindows(view)) {
                // Do not add padding if fitsSystemWindows is false
                return@doOnApplyWindowInsets insets
            }
            val top: Int = initialPadding.top
            val bottom: Int = initialPadding.bottom + insets.systemWindowInsetBottom
            val isRtl = ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL
            val systemWindowInsetLeft: Int = insets.systemWindowInsetLeft
            val systemWindowInsetRight: Int = insets.systemWindowInsetRight
            var start: Int = initialPadding.start
            var end: Int = initialPadding.end
            if (isRtl) {
                start += systemWindowInsetRight
                end += systemWindowInsetLeft
            } else {
                start += systemWindowInsetLeft
                end += systemWindowInsetRight
            }
            ViewCompat.setPaddingRelative(view, start, top, end, bottom)
            insets
        }
    }

    fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }

    // emulate that device has root rights in emulator
    // this allows running app in emulator without root rights
    fun isRooted(): Boolean {
        return if (!isEmulator())
            Shell.getShell().isRoot
        else
            true
    }

    fun getBatteryStatusText(batteryStatus: Int): String {
        val batteryStatusText = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            else -> "Unknown"
        }
        return batteryStatusText
    }
}
