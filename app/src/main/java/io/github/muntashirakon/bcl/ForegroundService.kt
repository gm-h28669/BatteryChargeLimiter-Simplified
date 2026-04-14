package io.github.muntashirakon.bcl

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.muntashirakon.bcl.Constants.INTENT_DISABLE_ACTION
import io.github.muntashirakon.bcl.Constants.NOTIFICATION_LIVE
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.activities.MainActivity
import io.github.muntashirakon.bcl.receivers.BatteryReceiver
import io.github.muntashirakon.bcl.receivers.ControlBatteryChargeReceiver
import io.github.muntashirakon.bcl.settings.PrefsFragment

/**
 * Created by harsha on 30/1/17.
 *
 * This is a Service that shows the notification about the current charging state
 * and supplies the context to the BatteryReceiver it is registering.
 *
 * 24/4/17 milux: Changed to make "restart" more efficient by avoiding the need to stop the service
 */
class ForegroundService : Service() {

    private val settings by lazy(LazyThreadSafetyMode.NONE) { this.getSharedPreferences(SETTINGS, 0) }
    private val prefs by lazy(LazyThreadSafetyMode.NONE) { Utils.getPrefs(this) }
    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        NotificationManagerCompat.from(this)

    }
    private val mNotifyBuilder by lazy(LazyThreadSafetyMode.NONE) {
        NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID)
    }
    private var notifyID = 1
    private var autoResetActive = false
    private var batteryReceiver: BatteryReceiver? = null

    /**
     * Enables the automatic reset on service shutdown
     */
    fun enableAutoReset() {
        autoResetActive = true
    }

    override fun onCreate() {
        Log.d(TAG, "Service created")
        isRunning = true

        settings.edit().putBoolean(NOTIFICATION_LIVE, true).apply()

        val channel = NotificationChannelCompat.Builder(
            Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        ).setName(getString(R.string.app_name))
         .build()
        notificationManager.createNotificationChannel(channel)

        val notification = mNotifyBuilder
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifyID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifyID, notification)
        }

        batteryReceiver = BatteryReceiver(this@ForegroundService)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
            Intent(this, ControlBatteryChargeReceiver::class.java).setAction(INTENT_DISABLE_ACTION),
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
        if (iconType == NOTIF_MAINTAIN) {
            mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_maintain)
        } else if (iconType == NOTIF_CHARGE) {
            mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_charge)
        }
    }

    fun updateNotification() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // No permission
            return
        }
        notificationManager.notify(notifyID, mNotifyBuilder.build())
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

    override fun onDestroy() {
        Log.d(TAG, "Service removed")
        if (autoResetActive && !ignoreAutoReset && prefs.getBoolean(PrefsFragment.KEY_AUTO_RESET_STATS, false)) {
            Utils.resetBatteryStats(this)
        }
        ignoreAutoReset = false

        settings.edit().putBoolean(NOTIFICATION_LIVE, false).apply()
        // unregister the battery event receiver
        unregisterReceiver(batteryReceiver)

        // make the BatteryReceiver and dependencies ready for garbage-collection
        batteryReceiver!!.detach(this)
        // clear the reference to the battery receiver for GC
        batteryReceiver = null

        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        val TAG = ForegroundService::class.java.simpleName

        // returns whether the service is running right now
        var isRunning = false
        private var ignoreAutoReset = false

        /**
         * Ignore the automatic reset when service is shut down the next time
         */
        internal fun ignoreAutoReset() {
            ignoreAutoReset = true
        }
    }
}
