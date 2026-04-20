package io.github.muntashirakon.bcl.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.muntashirakon.bcl.ChargeMode
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment
import androidx.core.content.edit

class MainFragment: Fragment() {
    private val minSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.min_slider)  }
    private val minText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.min_text) }
    private val maxSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.max_slider) }
    private val maxText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.max_text) }
    private val settings by lazy(LazyThreadSafetyMode.NONE) { activity?.getSharedPreferences(Constants.SETTINGS, 0) }
    private val statusText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.status) }
    private val batteryInfo by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_info) }
    private val batteryLevelText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_level) }
    private val serviceStatusDot by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<View>(R.id.service_status_dot) }
    private val serviceStatusBadge by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<View>(R.id.service_status_badge) }
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
                    requireContext().registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )!!
                )
                PrefsFragment.KEY_CUSTOM_CTRL_FILE_DATA,
                PrefsFragment.KEY_CONTROL_FILE -> {
                    updateUi()
                }
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

        serviceStatusBadge?.setOnClickListener {
            val isRunning = ForegroundService.isRunning
            Toast.makeText(
                requireContext(),
                if (isRunning) R.string.service_status_active else R.string.service_status_inactive,
                Toast.LENGTH_SHORT
            ).show()
        }

        setStatusCTRLFileData()

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED)
        intentFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        requireContext().registerReceiver(charging, intentFilter)
        updateUi()
    }

    override fun onStop() {
        super.onStop()
        requireContext().unregisterReceiver(charging)
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        serviceUpdateHandler.removeCallbacks(serviceUpdateRunnable)
    }

    private val switchListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
        if (!Utils.isCtrlFileSet(requireContext())) {
            if (isChecked) {
                buttonView.isChecked = false
                Toast.makeText(requireContext(), R.string.file_data, Toast.LENGTH_SHORT).show()
            }
            return@OnCheckedChangeListener
        }

        when (buttonView.id) {
            R.id.enable_switch -> {
                if (isChecked) {
                    disableChargeSwitch?.isChecked = false
                    settings?.edit { putBoolean(Constants.CHARGE_LIMIT_ENABLED, true) }
                    Utils.startServiceIfLimitEnabled(requireContext())
                } else {
                    settings?.edit { putBoolean(Constants.CHARGE_LIMIT_ENABLED, false) }
                    Utils.startService(requireContext())
                }
            }
            R.id.disable_charge_switch -> {
                if (isChecked) {
                    enableSwitch?.isChecked = false
                    settings?.edit { putBoolean(Constants.CHARGE_LIMIT_ENABLED, false) }
                    Utils.startService(requireContext())
                    Utils.changeState(requireContext(), ChargeMode.OFF)
                } else {
                    Utils.changeState(requireContext(), ChargeMode.ON)
                }
            }
        }
        updateServiceStatusIndicator()
    }

    private val charging: BroadcastReceiver = object : BroadcastReceiver() {
        private var previousStatus: Int = -1
        private var previousActuallyCharging: Boolean? = null

        fun getDefaultColor(context: Context) : Int {
            val typedValue = android.util.TypedValue()
            val resolved = context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
            return if (resolved) typedValue.data else ContextCompat.getColor(
                context,
                android.R.color.transparent
            )
        }

        override fun onReceive(context: Context, intent: Intent) {
            val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val level = Utils.getBatteryLevel(intent)
            val isActuallyCharging = Utils.isActuallyCharging(requireContext(), Constants.CURRENT_THRESHOLD_MA)

            statusText?.text = Utils.getBatteryStatusTextLocalized(requireContext(), batteryStatus, isActuallyCharging)
            batteryLevelText?.text = getString(R.string.percentage, level)
            updateBatteryInfo(intent)

            if (batteryStatus != previousStatus || isActuallyCharging != previousActuallyCharging) {
                statusCard?.setCardBackgroundColor(
                    when (batteryStatus) {
                        BatteryManager.BATTERY_STATUS_CHARGING,
                        BatteryManager.BATTERY_STATUS_DISCHARGING,
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                            ContextCompat.getColor(context, if (isActuallyCharging) R.color.charging_bg else R.color.discharging_bg)
                        else ->
                            getDefaultColor(context)
                    }
                )
                previousStatus = batteryStatus
                previousActuallyCharging = isActuallyCharging
            }
            updateServiceStatusIndicator()
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        Utils.getBatteryInfoAsync(
            requireContext(),
            intent,
            prefs?.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false) ?: false
        ) { info ->
            batteryInfo?.text = info
        }
    }

    private fun updateMinText(min: Int?) {
        val minVal = min ?: settings?.getInt(Constants.MIN, Constants.DEFAULT_MIN_PC) ?: Constants.DEFAULT_MIN_PC
        minText?.text = if (minVal == 0) getString(R.string.no_recharge) else getString(R.string.recharge_below, minVal)
    }

    private fun setStatusCTRLFileData() {
        val isCtrlFileSet = Utils.isCtrlFileSet(requireContext())
        val statusCtrlData = view?.findViewById<TextView>(R.id.status_ctrl_data)
        val experimentalLabel = view?.findViewById<TextView>(R.id.experimental_label)
        val issuesLabel = view?.findViewById<TextView>(R.id.issues_label)

        if (!isCtrlFileSet) {
            statusCtrlData?.text = getString(R.string.file_data)
            experimentalLabel?.visibility = View.GONE
            issuesLabel?.visibility = View.GONE
            return
        }

        val file = Utils.getCtrlFileData(requireContext())
        val on = Utils.getCtrlEnabledData(requireContext())
        val off = Utils.getCtrlDisabledData(requireContext())

        statusCtrlData?.text = getString(R.string.custom_ctrl_file_info_format, file, on, off)

        // Find the specific ControlFile object if it exists in the pre-configured list
        val ctrlFiles = Utils.getCtrlFiles(requireContext())
        val cf = ctrlFiles.find { it.file == file }

        if (cf != null) {
            experimentalLabel?.visibility = if (cf.experimental) View.VISIBLE else View.GONE
            issuesLabel?.visibility = if (cf.issues) View.VISIBLE else View.GONE
        } else {
            // If it's a custom file not in the list, we assume it's experimental for safety
            experimentalLabel?.visibility = View.VISIBLE
            issuesLabel?.visibility = View.GONE
        }
    }

    private fun updateUi() {
        setStatusCTRLFileData()
        val isCtrlFileSet = Utils.isCtrlFileSet(requireContext())
        val limitEnabled = settings?.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false) ?: false

        enableSwitch?.isEnabled = isCtrlFileSet
        disableChargeSwitch?.isEnabled = isCtrlFileSet

        if (!isCtrlFileSet) {
            enableSwitch?.isChecked = false
            disableChargeSwitch?.isChecked = false
            Utils.stopService(requireContext())
        } else {
            enableSwitch?.isChecked = limitEnabled
            // If control file is set, ensure service is running (either in Limit mode or Monitoring mode)
            if (!ForegroundService.isRunning) {
                Utils.startService(requireContext())
            }
        }

        val limit = settings?.getInt(Constants.LIMIT, Constants.DEFAULT_LIMIT_PC) ?: Constants.DEFAULT_LIMIT_PC
        maxSlider?.value = limit.toFloat()
        maxText?.text = getString(R.string.limit, limit)

        val min = settings?.getInt(Constants.MIN, Constants.DEFAULT_MIN_PC) ?: Constants.DEFAULT_MIN_PC
        minSlider?.value = min.toFloat()
        updateMinText(min)

        updateServiceStatusIndicator()
    }

    private fun updateServiceStatusIndicator() {
        // Use a delay slightly longer than the service start delay (500ms) to ensure it's up
        serviceUpdateHandler.postDelayed({
            if (!isAdded) return@postDelayed
            val isRunning = ForegroundService.isRunning
            
            serviceStatusDot?.backgroundTintList = ColorStateList.valueOf(
                if (isRunning) ContextCompat.getColor(requireContext(), R.color.darkGreen)
                else ContextCompat.getColor(requireContext(), R.color.red)
            )

            serviceStatusBadge?.apply {
                val isRunning = ForegroundService.isRunning
                backgroundTintList = ColorStateList.valueOf(
                    if (isRunning) ContextCompat.getColor(requireContext(), R.color.lightGreen)
                    else {
                        // Use a neutral color for inactive state (Surface Container High)
                        val typedValue = android.util.TypedValue()
                        val resolved = requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
                        if (resolved) typedValue.data else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                    }
                )
                alpha = if (isRunning) 1.0f else 0.6f
            }
        }, 800)
    }
}
