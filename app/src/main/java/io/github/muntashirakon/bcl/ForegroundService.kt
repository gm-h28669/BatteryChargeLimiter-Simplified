package io.github.muntashirakon.bcl

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.muntashirakon.bcl.activities.MainActivity
import io.github.muntashirakon.bcl.receivers.BatteryControlReceiver
import io.github.muntashirakon.bcl.receivers.BatteryMonitorReceiver
import io.github.muntashirakon.bcl.receivers.ControlBatteryChargeReceiver
import io.github.muntashirakon.bcl.receivers.PowerConnectionReceiver
import io.github.muntashirakon.bcl.settings.PrefsFragment
import androidx.core.content.edit

/**
 * Created by harsha on 30/1/17.
 *
 * This is a Service that shows the notification about the current charging state
 * and supplies the context to the BatteryReceiver it is registering.
 *
 * 24/4/17 milux: Changed to make "restart" more efficient by avoiding the need to stop the service
 */
class ForegroundService : Service() {

    private val settings by lazy(LazyThreadSafetyMode.NONE) { this.getSharedPreferences(Constants.SETTINGS, 0) }
    private val prefs by lazy(LazyThreadSafetyMode.NONE) { Utils.getPrefs(this) }
    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        NotificationManagerCompat.from(this)
    }
    private val mNotifyBuilder by lazy(LazyThreadSafetyMode.NONE) {
        NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID)
            .setOnlyAlertOnce(true)
    }
    private var notifyID = 1
    private var autoResetActive = false
    private var batteryControlReceiver: BatteryControlReceiver? = null
    private var batteryMonitorReceiver: BatteryMonitorReceiver? = null
    private var powerConnectionReceiver: PowerConnectionReceiver? = null

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences, key: String? ->
        if (key == Constants.CHARGE_LIMIT_ENABLED) {
            switchReceivers()
        }
    }

    /**
     * Enables the automatic reset on service shutdown
     */
    fun enableAutoReset() {
        autoResetActive = true
    }

    override fun onCreate() {
        Log.d(TAG, "$TAG created")
        isRunning = true

        settings.edit { putBoolean(Constants.NOTIFICATION_LIVE, true) }

        val channel = NotificationChannelCompat.Builder(
            Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        ).setName(getString(R.string.app_name))
         .build()
        notificationManager.createNotificationChannel(channel)

        val notification = mNotifyBuilder
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setContentTitle(getString(R.string.please_wait))
            .setContentInfo(getString(R.string.please_wait))
            .setSmallIcon(R.drawable.ic_notif_charge)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notifyID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifyID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifyID, notification)
        }

        settings.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        switchReceivers()

        //  since Android 8+ (API 26) manifest declared receivers will not get power connect/disconnect events
        //  we need to create and register dynamically the broadcast receiver in foreground service
        powerConnectionReceiver = PowerConnectionReceiver(this)
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerConnectionReceiver, powerFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service start command received")
        ignoreAutoReset = false
        return super.onStartCommand(intent, flags, startId)
    }

    fun setNotificationActionText(actionText: String) {
        mNotifyBuilder.clearActions()
        val flagImmutable: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntentOpenApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flagImmutable)
        val pendingIntentDisable = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, ControlBatteryChargeReceiver::class.java).setAction(Constants.INTENT_DISABLE_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )
        mNotifyBuilder.addAction(0, actionText, pendingIntentDisable)
            .addAction(0, getString(R.string.open_app), pendingIntentOpenApp)
    }

    fun setNotificationTitle(title: String) {
        mNotifyBuilder.setContentTitle(title)
    }

    fun setNotificationContentText(contentText: String) {
        mNotifyBuilder.setContentText(contentText)
    }

    fun setNotificationIcon(iconType: String) {
        when (iconType) {
            Constants.NOTIF_MAINTAIN -> mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_maintain)
            Constants.NOTIF_CHARGE -> mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_charge)
            Constants.NOTIF_MONITOR -> mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_monitor)
        }
    }

    fun updateNotification() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // No permission
            return
        }
        notificationManager.notify(notifyID, mNotifyBuilder.build())
    }

    fun refreshNotification() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            batteryControlReceiver?.onReceive(this, batteryIntent)
            batteryMonitorReceiver?.onReceive(this, batteryIntent)
        }
    }

    fun setNotificationSound() {
        try {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, soundUri)
            r.play()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play notification sound", e)
        }
    }

    private fun switchReceivers() {
        val limitEnabled = settings.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
        Log.d(TAG, "Switching receivers. Limit enabled: $limitEnabled")

        // Unregister existing receivers
        batteryControlReceiver?.let {
            unregisterReceiver(it)
            it.detach(this)
        }
        batteryControlReceiver = null

        batteryMonitorReceiver?.let {
            unregisterReceiver(it)
            it.detach()
        }
        batteryMonitorReceiver = null

        if (limitEnabled) {
            batteryControlReceiver = BatteryControlReceiver(this)
            registerReceiver(batteryControlReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            batteryMonitorReceiver = BatteryMonitorReceiver(this)
            registerReceiver(batteryMonitorReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // Manually trigger once to show initial info
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                batteryMonitorReceiver?.onReceive(this, batteryIntent)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service removed")
        if (autoResetActive && !ignoreAutoReset && prefs.getBoolean(PrefsFragment.KEY_AUTO_RESET_STATS, false)) {
            Utils.resetBatteryStats(this)
        }
        ignoreAutoReset = false

        settings.edit { putBoolean(Constants.NOTIFICATION_LIVE, false) }
        settings.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)

        // unregister the battery and power connection receiver
        batteryControlReceiver?.let {
            unregisterReceiver(it)
            it.detach(this)
        }
        batteryMonitorReceiver?.let {
            unregisterReceiver(it)
            it.detach()
        }
        unregisterReceiver(powerConnectionReceiver)

        // make the receivers and dependencies ready for garbage-collection
        powerConnectionReceiver?.detach()

        // clear the reference to the receivers for GC
        batteryControlReceiver = null
        batteryMonitorReceiver = null
        powerConnectionReceiver = null

        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        val TAG: String = ForegroundService::class.java.simpleName

        // returns whether the service is running right now
        var isRunning = false
        private var ignoreAutoReset = false

        /**
         * Ignore the automatic reset when service is shut down the next time
         */
        internal fun ignoreAutoReset() {
            ignoreAutoReset = true
        }

        /**
         * Stops the foreground service if it's running
         */
        fun stopService(context: Context) {
            if (isRunning) {
                val intent = Intent(context, ForegroundService::class.java)
                context.stopService(intent)
                isRunning = false
            }
        }
    }
}
