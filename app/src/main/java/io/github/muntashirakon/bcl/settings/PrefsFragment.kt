package io.github.muntashirakon.bcl.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.activities.CustomCtrlFileDataActivity

class PrefsFragment : PreferenceFragmentCompat() {

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateSummaries()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        var dialogFragment: DialogFragment? = null
        if (preference is ControlFilePreference) {
            dialogFragment = ControlFileDialogFragmentCompat.newInstance(preference.key)
        }

        if (dialogFragment != null) {
            val settings = requireContext().getSharedPreferences(Constants.SETTINGS, 0)
            if (!settings.getBoolean("has_opened_ctrl_file", false)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.control_file_heads_up_title)
                    .setMessage(R.string.control_file_heads_up_desc)
                    .setCancelable(false)
                    .setPositiveButton(R.string.control_understand) { _, _ ->
                        settings.edit().putBoolean("has_opened_ctrl_file", true).apply()
                        openControlFileDialogFragment(dialogFragment)
                    }.show()
            } else {
                openControlFileDialogFragment(dialogFragment)
            }
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)

        setHasOptionsMenu(true)

        val theme: ListPreference = findPreference(KEY_THEME)!!
        val customCtrlFileDataSwitch: SwitchPreferenceCompat = findPreference(KEY_CUSTOM_CTRL_FILE_DATA)!!
        val ctrlFilePreference: ControlFilePreference = findPreference(KEY_CONTROL_FILE)!!
        val ctrlFileSetupPreference: Preference = findPreference(KEY_CUSTOM_CTRL_FILE_SETUP)!!

        theme.setOnPreferenceChangeListener { preference, _ ->
            if (preference is ListPreference) {
                requireActivity().recreate()
            }
            true
        }

        customCtrlFileDataSwitch.setOnPreferenceChangeListener { _, newValue ->
            val useCustomControlFile = newValue as Boolean
            ctrlFilePreference.isVisible = !useCustomControlFile
            ctrlFileSetupPreference.isVisible = useCustomControlFile
            ctrlFileSetupPreference.isEnabled = useCustomControlFile
            true
        }

        ctrlFileSetupPreference.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.control_file_alert_title)
                .setMessage(R.string.control_file_alert_desc)
                .setCancelable(false)
                .setPositiveButton(R.string.control_understand) { _, _ ->
                    val ctrlFileIntent = Intent(requireContext(), CustomCtrlFileDataActivity::class.java)
                    startActivity(ctrlFileIntent)
                }
                .show()
            true
        }

        if (customCtrlFileDataSwitch.isChecked) {
            ctrlFilePreference.isVisible = false
            ctrlFileSetupPreference.isVisible = true
            ctrlFileSetupPreference.isEnabled = true
        } else {
            ctrlFilePreference.isVisible = true
            ctrlFileSetupPreference.isVisible = false
            ctrlFileSetupPreference.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        updateSummaries()
        Utils.getPrefs(requireContext()).registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onPause() {
        Utils.getPrefs(requireContext()).unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onPause()
    }

    private fun updateSummaries() {
        // Update Custom Control File Setup summary
        val ctrlFileSetupPreference: Preference? = findPreference(KEY_CUSTOM_CTRL_FILE_SETUP)
        if (ctrlFileSetupPreference != null) {
            val settings = requireContext().getSharedPreferences(Constants.SETTINGS, 0)
            val savedPathData = settings.getString(Constants.SAVED_PATH_DATA, null)
            val savedEnabledData = settings.getString(Constants.SAVED_ENABLED_DATA, null)
            val savedDisabledData = settings.getString(Constants.SAVED_DISABLED_DATA, null)

            if (savedPathData != null && savedEnabledData != null && savedDisabledData != null) {
                ctrlFileSetupPreference.summary = getString(
                    R.string.custom_ctrl_file_info_format,
                    savedPathData,
                    savedEnabledData,
                    savedDisabledData
                )
            } else {
                ctrlFileSetupPreference.setSummary(R.string.custom_ctrl_file_data_setup_summary)
            }
        }

        // Update Control File summary
        val ctrlFilePreference: ControlFilePreference? = findPreference(KEY_CONTROL_FILE)
        if (ctrlFilePreference != null) {
            val prefs = Utils.getPrefs(requireContext())
            val currentFile: String? = prefs.getString(KEY_CONTROL_FILE, null)
            if (currentFile != null) {
                ctrlFilePreference.summary = currentFile
            }
        }
    }

    private fun openControlFileDialogFragment(dialogFragment: DialogFragment) {
        CtrlFileHelper.validateFiles(requireContext()) {
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(this.parentFragmentManager, ControlFileDialogFragmentCompat::class.java.simpleName)
        }
    }

    companion object {
        const val KEY_CONTROL_FILE = "control_file"
        const val KEY_CUSTOM_CTRL_FILE_DATA = "custom_ctrl_file_data"
        const val KEY_CUSTOM_CTRL_FILE_SETUP = "custom_ctrl_file_setup"
        const val KEY_TEMP_FAHRENHEIT = "temp_fahrenheit"
        const val KEY_IMMEDIATE_POWER_INTENT_HANDLING = "immediate_power_intent_handling"
        const val KEY_NOTIFICATION_SOUND = "notification_sound"
        const val KEY_AUTO_RESET_STATS = "auto_reset_stats"
        const val KEY_ENFORCE_CHARGE_LIMIT = "enforce_charge_limit"
        const val KEY_ALWAYS_WRITE_CF = "always_write_cf"
        const val KEY_DISABLE_AUTO_RECHARGE = "disable_auto_recharge"
        const val KEY_THEME = "theme"
    }
}
