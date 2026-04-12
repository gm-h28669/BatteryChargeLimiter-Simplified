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
import java.util.Locale
import androidx.preference.PreferenceManager
import com.google.android.material.internal.ViewUtils.doOnApplyWindowInsets
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.Constants.CHARGE_LIMIT_ENABLED
import io.github.muntashirakon.bcl.Constants.CHARGE_OFF_KEY
import io.github.muntashirakon.bcl.Constants.CHARGE_ON_KEY
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.DEFAULT_DISABLED
import io.github.muntashirakon.bcl.Constants.DEFAULT_ENABLED
import io.github.muntashirakon.bcl.Constants.DEFAULT_FILE
import io.github.muntashirakon.bcl.Constants.FILE_KEY
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIFICATION_LIVE
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.settings.PrefsFragment
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


object Utils {
    private val TAG = Utils::class.java.simpleName
    const val CHARGE_ON = 0
    const val CHARGE_OFF = 1
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

    fun changeState(context: Context, chargeMode: Int) {
        val preferences = getPrefs(context)
        val alwaysWrite = preferences.getBoolean(PrefsFragment.KEY_ALWAYS_WRITE_CF, false)

        val file = getCtrlFileData(context)
        val newState = if (chargeMode == CHARGE_ON) {
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
                "mount -o rw,remount $file", "chmod u+w $file",
                "echo \"$newState\" > $file"
            )
        }

        if (alwaysWrite) {
            Shell.cmd(switchCommands.joinToString(separator = " && ")).submit()
        } else {
            Shell.cmd("cat $file").submit {
                if (it.out.size == 0 || it.out[0] != newState) {
                    setChangePending()
                    Shell.cmd(switchCommands.joinToString(separator = " && ")).submit()
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
            .edit().putString(FILE_KEY, cf.file)
            .putString(CHARGE_ON_KEY, cf.chargeOn)
            .putString(CHARGE_OFF_KEY, cf.chargeOff).apply()
        //Respawn the service if necessary
        startServiceIfLimitEnabled(context)
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
    fun getAverageCurrent(batteryManager: BatteryManager): Int {
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
            rawAverageCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        }
        return rawAverageCurrent
    }

    fun getBatteryInfo(context: Context, intent: Intent, useFahrenheit: Boolean): String {
        val batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rawAverageCurrent = getAverageCurrent(batteryManager)

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
            temperatureStr
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
//        try {
//            // new technique for PureNexus-powered devices
//            val helperClass = Class.forName("com.android.internal.os.BatteryStatsHelper")
//            val constructor = helperClass.getConstructor(Context::class.java,
//                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
//            val instance = constructor.newInstance(context, false, false)
//            val createMethod = helperClass.getMethod("create", Bundle::class.javaPrimitiveType)
//            createMethod.invoke(instance, null)
//            val resetMethod = helperClass.getMethod("resetStatistics")
//            resetMethod.invoke(instance)
//            Toast.makeText(context, R.string.stats_reset_success, Toast.LENGTH_SHORT).show()
//        } catch (e: Exception) {
//            Log.i("New reset method failed", e.message, e)
        // On Exception, fall back to conventional method
        Shell.cmd("dumpsys batterystats --reset").submit {
            if (it.isSuccess) {
                Toast.makeText(context, R.string.stats_reset_success, Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Statistics reset failed")
            }
        }
//        }
    }

    fun setLimit(limit: Int, settings: SharedPreferences) {
        val min = settings.getInt(MIN, Constants.DEFAULT_MIN_PC)
        val edit = settings.edit().putInt(LIMIT, limit)
        if (min >= limit) {
            edit.putInt(MIN, (limit - 1).coerceAtLeast(0))
        }
        edit.apply()
    }

    fun handleLimitChange(context: Context, newLimit: Any?) {
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
                settings.edit().putBoolean(CHARGE_LIMIT_ENABLED, false).apply()
            } else if (limit in Constants.MIN_ALLOWED_LIMIT_PC until Constants.MAX_ALLOWED_LIMIT_PC) {
                val settings = getSettings(context)
                // set the new limit
                setLimit(limit, settings)
                Toast.makeText(
                    context, context.getString(R.string.intent_limit_accepted, limit),
                    Toast.LENGTH_SHORT
                ).show()
                if (!settings.getBoolean(NOTIFICATION_LIVE, false)) {
                    settings.edit().putBoolean(CHARGE_LIMIT_ENABLED, true).apply()
                    startServiceIfLimitEnabled(context)
                }
            } else {
                throw NumberFormatException("Battery limit out of range!")
            }
        } catch (fe: NumberFormatException) {
            Toast.makeText(context, R.string.intent_limit_invalid, Toast.LENGTH_SHORT).show()
        }

    }

    fun startServiceIfLimitEnabled(context: Context) {
        if (!getSettings(context).getBoolean(CHARGE_LIMIT_ENABLED, false)) {
            return
        }
        if (getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
            changeState(context, CHARGE_ON)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            ContextCompat.startForegroundService(context, Intent(context, ForegroundService::class.java))
            // display service enabled Toast message if not disabled in settings
            if (!getPrefs(context).getBoolean("hide_toast_on_service_changes", false)) {
                Toast.makeText(context, R.string.service_enabled, Toast.LENGTH_SHORT).show()
            }
        }, CHARGING_CHANGE_TOLERANCE_MS)
    }

    fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun getSettings(context: Context): SharedPreferences {
        return context.getSharedPreferences(SETTINGS, 0)
    }

    fun stopService(context: Context, ignoreAutoReset: Boolean = true) {
        val wasServiceRunning = ForegroundService.isRunning
        if (ignoreAutoReset) {
            ForegroundService.ignoreAutoReset()
        }
        context.stopService(Intent(context, ForegroundService::class.java))
        if (!getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
            changeState(context, CHARGE_ON)
        }
        // display service disabled Toast message if not disabled in settings
        if (wasServiceRunning && !getPrefs(context).getBoolean("hide_toast_on_service_changes", false)) {
            Toast.makeText(context, R.string.service_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    fun getCtrlFileData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_PATH_DATA, DEFAULT_FILE)!!
        } else {
            // Custom Data Disabled
            settings.getString(FILE_KEY, DEFAULT_FILE)!!
        }
    }

    fun getCtrlEnabledData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_ENABLED_DATA, DEFAULT_ENABLED)!!
        } else {
            // Custom Data Disabled
            settings.getString(CHARGE_ON_KEY, DEFAULT_ENABLED)!!
        }
    }

    fun getCtrlDisabledData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_DISABLED_DATA, DEFAULT_DISABLED)!!
        } else {
            // Custom Data Disabled
            settings.getString(CHARGE_OFF_KEY, DEFAULT_DISABLED)!!
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
}
