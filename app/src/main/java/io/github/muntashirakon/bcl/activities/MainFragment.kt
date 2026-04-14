package io.github.muntashirakon.bcl.activities

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.muntashirakon.bcl.*
import io.github.muntashirakon.bcl.settings.PrefsFragment

class MainFragment: Fragment() {
    private val minSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.min_slider)  }
    private val minText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.min_text) }
    private val maxSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.max_slider) }
    private val maxText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.max_text) }
    private val settings by lazy(LazyThreadSafetyMode.NONE) { activity?.getSharedPreferences(Constants.SETTINGS, 0) }
    private val statusText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.status) }
    private val batteryInfo by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_info) }
    private val batteryLevelText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_level) }
    private val enableSwitch by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<SwitchMaterial>(R.id.enable_switch) }
    private val disableChargeSwitch by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<SwitchMaterial>(R.id.disable_charge_switch) }
    private val statusCard by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.status_card) }
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var prefs: SharedPreferences? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Utils.startServiceIfLimitEnabled(requireContext())
        } else requireActivity().finishAndRemoveTask()
    }

    // Debounce mechanism to prevent frequent service updates and disk writes while sliders are being dragged.
    private val serviceUpdateHandler = Handler(Looper.getMainLooper())
    private val serviceUpdateRunnable = Runnable {
        if (isAdded) {
            val max = maxSlider?.value?.toInt() ?: Constants.DEFAULT_LIMIT_PC
            val min = minSlider?.value?.toInt() ?: Constants.DEFAULT_MIN_PC

            // Save the settled values to disk once
            settings?.edit()?.apply {
                putInt(Constants.LIMIT, max)
                putInt(Constants.MIN, min)
                apply()
            }

            // Notify the service to re-evaluate hardware state
            Utils.startServiceIfLimitEnabled(requireContext())
        }
    }

    /**
     * Schedules a service update to run after a 1000ms delay.
     * If called again before the delay expires, the previous request is canceled and the timer resets.
     */
    private fun triggerDebouncedServiceUpdate() {
        serviceUpdateHandler.removeCallbacks(serviceUpdateRunnable)
        serviceUpdateHandler.postDelayed(serviceUpdateRunnable, 1000)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.applyWindowInsetsAsPaddingNoTop(view)
        prefs = Utils.getPrefs(requireContext())
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PrefsFragment.KEY_TEMP_FAHRENHEIT -> updateBatteryInfo(
                    context?.registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )!!
                )
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        val resetBatteryStatsButton = view.findViewById<Button>(R.id.reset_battery_stats)
//        val autoResetSwitch = view.findViewById(R.id.auto_stats_reset) as CheckBox
//        val notificationSound = view.findViewById(R.id.notification_sound) as CheckBox

//        autoResetSwitch.isChecked = settings?.getBoolean(AUTO_RESET_STATS, false)
//        notificationSound.isChecked = settings?.getBoolean(NOTIFICATION_SOUND, false)
        maxSlider?.valueFrom = Constants.MIN_ALLOWED_LIMIT_PC.toFloat()
        maxSlider?.valueTo = Constants.MAX_ALLOWED_LIMIT_PC.toFloat()
        minSlider?.valueFrom = 0f
        minSlider?.valueTo = (Constants.MAX_ALLOWED_LIMIT_PC - 1).toFloat()

        enableSwitch?.setOnCheckedChangeListener(switchListener)
        disableChargeSwitch?.setOnCheckedChangeListener(switchListener)
        maxSlider?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val max = value.toInt()
            maxText?.text = getString(R.string.limit, max)
            
            // UI-only sync for min slider (validation)
            val min = minSlider?.value?.toInt() ?: Constants.DEFAULT_MIN_PC
            if (min >= max) {
                val newMin = (max - 1).coerceAtLeast(0)
                minSlider?.value = newMin.toFloat()
                updateMinText(newMin)
            }

            // Trigger debounced write and service update
            triggerDebouncedServiceUpdate()
        }

        minSlider?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val min = value.toInt()
            val max = maxSlider?.value?.toInt() ?: Constants.DEFAULT_LIMIT_PC
            
            if (min >= max) {
                val newMax = min + 1
                maxSlider?.value = newMax.toFloat()
                maxText?.text = getString(R.string.limit, newMax)
            }
            
            updateMinText(min)

            // Trigger debounced write and service update
            triggerDebouncedServiceUpdate()
        }

        resetBatteryStatsButton.setOnClickListener { Utils.resetBatteryStats(requireContext()) }
//        autoResetSwitch.setOnCheckedChangeListener { _, isChecked ->
//            settings.edit().putBoolean(AUTO_RESET_STATS, isChecked).apply() }
//        notificationSound.setOnCheckedChangeListener { _, isChecked ->
//            settings.edit().putBoolean(NOTIFICATION_SOUND, isChecked).apply() }

        setStatusCTRLFileData()

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        context?.registerReceiver(charging, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // the limits could have been changed by an Intent, so update the UI here
        updateUi()
        setStatusCTRLFileData()
    }

    override fun onStop() {
        context?.unregisterReceiver(charging)
        super.onStop()
    }

    override fun onDestroy() {
        serviceUpdateHandler.removeCallbacks(serviceUpdateRunnable)
        prefs?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onDestroy()
    }

    //OnCheckedChangeListener for Switch elements
    private val switchListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
        when (buttonView.id) {
            R.id.enable_switch -> {
                settings?.edit()?.putBoolean(Constants.CHARGE_LIMIT_ENABLED, isChecked)?.apply()
                if (isChecked) {
                    Utils.startServiceIfLimitEnabled(requireContext())
                    disableSwitches(listOf(disableChargeSwitch))
                } else {
                    Utils.stopService(requireContext())
                    enableSwitches(listOf(disableChargeSwitch))
                }
                EnableWidget.updateWidget(requireContext(), isChecked)
            }
            R.id.disable_charge_switch -> {
                if (isChecked) {
                    Utils.changeState(requireContext(), ChargeMode.OFF)
                    settings?.edit()?.putBoolean(Constants.DISABLE_CHARGE_NOW, true)?.apply()
                    disableSwitches(listOf(enableSwitch))
                } else {
                    Utils.changeState(requireContext(), ChargeMode.ON)
                    settings?.edit()?.putBoolean(Constants.DISABLE_CHARGE_NOW, false)?.apply()
                    enableSwitches(listOf(enableSwitch))
                }
            }
        }
    }

    //to update battery status on UI
    private val charging = object : BroadcastReceiver() {
        private var previousStatus = BatteryManager.BATTERY_STATUS_UNKNOWN

        override fun onReceive(context: Context, intent: Intent) {
            val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            if (batteryStatus != previousStatus) {
                previousStatus = batteryStatus
                when (batteryStatus) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> {
                        statusText?.setText(R.string.charging)
                        val green = ContextCompat.getColor(context, R.color.darkGreen)
                        statusText?.setTextColor(green)
                        batteryLevelText?.setTextColor(green)
                        val chargingColor = ContextCompat.getColor(context, R.color.charging_bg)
                        statusCard?.setCardBackgroundColor(chargingColor)
                    }
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                        statusText?.setText(R.string.discharging)
                        val orange = ContextCompat.getColor(context, R.color.orange)
                        statusText?.setTextColor(orange)
                        batteryLevelText?.setTextColor(orange)
                        val dischargingColor = ContextCompat.getColor(context, R.color.discharging_bg)
                        statusCard?.setCardBackgroundColor(dischargingColor)
                    }
                    BatteryManager.BATTERY_STATUS_FULL -> {
                        statusText?.setText(R.string.full)
                        val green = ContextCompat.getColor(context, R.color.darkGreen)
                        statusText?.setTextColor(green)
                        batteryLevelText?.setTextColor(green)
                        val chargingColor = ContextCompat.getColor(context, R.color.charging_bg)
                        statusCard?.setCardBackgroundColor(chargingColor)
                    }
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> {
                        statusText?.setText(R.string.not_charging)
                        val orange = ContextCompat.getColor(context, R.color.orange)
                        statusText?.setTextColor(orange)
                        batteryLevelText?.setTextColor(orange)
                        val dischargingColor = ContextCompat.getColor(context, R.color.discharging_bg)
                        statusCard?.setCardBackgroundColor(dischargingColor)
                    }
                    else -> {
                        statusText?.setText(R.string.unknown)
                        val red = ContextCompat.getColor(context, R.color.red)
                        statusText?.setTextColor(red)
                        batteryLevelText?.setTextColor(red)
                    }
                }
            }
            batteryLevelText?.text = getString(R.string.percentage, Utils.getBatteryLevel(intent))
            updateBatteryInfo(intent)
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        Utils.getBatteryInfoAsync(
            requireContext(), intent,
            prefs?.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)!!
        ) { info ->
            batteryInfo?.text = info
        }
    }

//    private fun hideKeybord() {
//        val inputManager = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
//        if (inputManager.isAcceptingText) {
//            inputManager.hideSoftInputFromWindow(activity?.currentFocus?.windowToken, 0)
//        }
//    }

    private fun disableSwitches(switches: List<SwitchMaterial?>) {
        for (switch in switches) {
            switch?.isEnabled = false
        }
    }

    private fun enableSwitches(switches: List<SwitchMaterial?>) {
        for (switch in switches) {
            switch?.isEnabled = true
        }
    }

    private fun updateMinText(min: Int?) {
        when (min) {
            0 -> minText?.setText(R.string.no_recharge)
            else -> minText?.text = getString(R.string.recharge_below, min)
        }
    }

    private fun setStatusCTRLFileData() {
        val statusCTRLData = view?.findViewById<TextView>(R.id.status_ctrl_data)
        val context = requireContext()
        val settings = Utils.getSettings(context)
        val preferences = Utils.getPrefs(context)

        val isSet = if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            settings.contains(Constants.SAVED_PATH_DATA)
        } else {
            settings.contains(Constants.FILE_KEY)
        }

        if (isSet) {
            statusCTRLData?.text = String.format(
                "%s, %s, %s",
                Utils.getCtrlFileData(context),
                Utils.getCtrlEnabledData(context),
                Utils.getCtrlDisabledData(context)
            )
        } else {
            statusCTRLData?.setText(R.string.file_data)
        }
    }

    private fun updateUi() {
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            batteryLevelText?.text = getString(R.string.percentage, Utils.getBatteryLevel(intent))
        }
        enableSwitch?.isChecked = settings?.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false) == true
        disableChargeSwitch?.isChecked = settings?.getBoolean(Constants.DISABLE_CHARGE_NOW, false) == true
        val max = settings?.getInt(Constants.LIMIT, Constants.DEFAULT_LIMIT_PC) ?: Constants.DEFAULT_LIMIT_PC
        val min = settings?.getInt(Constants.MIN, Constants.DEFAULT_MIN_PC) ?: Constants.DEFAULT_MIN_PC
        
        maxSlider?.value = max.toFloat()
        minSlider?.value = min.toFloat()

        maxText?.text = getString(R.string.limit, max)
        updateMinText(min)
    }
}