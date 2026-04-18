package io.github.muntashirakon.bcl

/**
 * Created by Michael on 26.03.2017.
 *
 * This class holds constants for internal use that are not shown to the user
 */

object Constants {
    const val SETTINGS = "Settings"
    const val SETTINGS_VERSION = "SettingsVersion"

    const val FILE_KEY = "ctrl_file"
    const val CHARGE_ON_KEY = "charge_on"
    const val CHARGE_OFF_KEY = "charge_off"

    const val LIMIT = "limit"
    const val MIN = "min"
    const val CHARGE_LIMIT_ENABLED = "enable"
    const val NOTIFICATION_LIVE = "notificationLive"

    // ms after reaching limit, where the "unplug" event is recognized as power cut instead of action unplugging
    const val POWER_CHANGE_TOLERANCE_MS: Long = 3000
    const val CHARGING_CHANGE_TOLERANCE_MS: Long = 500
    const val MAX_BACK_OFF_TIME: Long = 30000

    const val MAX_ALLOWED_LIMIT_PC: Int = 100
    const val DEFAULT_LIMIT_PC: Int = 50
    const val DEFAULT_MIN_PC: Int = 40
    const val MIN_ALLOWED_LIMIT_PC: Int = 1
    const val CURRENT_THRESHOLD_MA: Int = 100

    const val NOTIF_MAINTAIN = "ic_maintain"
    const val NOTIF_CHARGE = "ic_charge"

    const val INTENT_TOGGLE_ACTION = BuildConfig.APPLICATION_ID + ".action.TOGGLE"
    const val INTENT_DISABLE_ACTION = BuildConfig.APPLICATION_ID + ".action.DISABLE"
    const val INTENT_CHANGE_LIMIT_ACTION = BuildConfig.APPLICATION_ID + ".action.CHANGE_LIMIT"
    const val FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID = BuildConfig.APPLICATION_ID + ".action.FOREGROUND_SERVICE_V2"

    const val SAVED_PATH_DATA = "saved_ctrl_path_data"
    const val SAVED_ENABLED_DATA = "saved_ctrl_enabled_data"
    const val SAVED_DISABLED_DATA = "saved_ctrl_disabled_data"

    const val LIGHT = "light"
    const val DARK = "dark"
    const val BLACK = "black"

    const val PREVIOUSLY_STARTED = "Previously Started"
}
