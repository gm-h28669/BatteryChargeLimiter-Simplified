// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils

class ControlBatteryChargeReceiver : BroadcastReceiver() {
    companion object {
        val TAG = ControlBatteryChargeReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            Constants.INTENT_CHANGE_LIMIT_ACTION -> {
                Log.d(TAG, "Event received: Charge limit has changed")
                Utils.handleLimitChange(context, intent.extras?.get(Intent.EXTRA_TEXT))
            }

            Constants.INTENT_DISABLE_ACTION -> {
                Log.d(TAG, "User has requested to stop service")
                Utils.stopService(context, false)
            }

            Constants.INTENT_TOGGLE_ACTION -> {
                Log.d(TAG, "Event received: Toggle service on/off")
                val settings = Utils.getSettings(context)
                if (Utils.isRooted()) {
                    if (!Utils.isCtrlFileSet(context)) {
                        Toast.makeText(context, R.string.file_data, Toast.LENGTH_SHORT).show()
                        return
                    }
                    val enable = !settings.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
                    settings.edit().putBoolean(Constants.CHARGE_LIMIT_ENABLED, enable).apply()
                    if (enable) {
                        Utils.startServiceIfLimitEnabled(context)
                    } else {
                        Utils.stopService(context)
                    }
                } else {
                    Toast.makeText(context, R.string.root_denied, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
