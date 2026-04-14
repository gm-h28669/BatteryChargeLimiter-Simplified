package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.muntashirakon.bcl.Utils

/**
 * Created by Michael on 20.04.2017.
 *
 * Triggered when the phone finished booting.
 * Checks whether power supply is attached and starts the foreground service if necessary.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        val TAG = BootReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d(TAG, "Booting completed. Start service if limit checking enabled")
            Utils.startServiceIfLimitEnabled(context)
        }
    }
}
