package io.github.muntashirakon.bcl.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import androidx.core.content.edit

class CustomCtrlFileDataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.setTheme(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_ctrl_file_data)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Utils.applyWindowInsetsAsPaddingNoTop(findViewById(R.id.scrollView))

        var customPathData: String? = null
        var customEnabledData: String? = null
        var customDisabledData: String? = null
        val editPathData = findViewById<EditText>(R.id.edit_path_file)
        val editEnabledData = findViewById<EditText>(R.id.edit_path_enabled)
        val editDisabledData = findViewById<EditText>(R.id.edit_path_disabled)
        val btnUpdateData = findViewById<Button>(R.id.btn_update_custom)
        val settings = this.getSharedPreferences(Constants.SETTINGS, 0)
        val savedPathData = settings.getString(Constants.SAVED_PATH_DATA, null)
        val savedEnabledData = settings.getString(Constants.SAVED_ENABLED_DATA, null)
        val savedDisabledData = settings.getString(Constants.SAVED_DISABLED_DATA, null)
        val updatedDataText = findViewById<TextView>(R.id.custom_data_updated)

        if (savedPathData != null && savedEnabledData != null && savedDisabledData != null) {
            updatedDataText.text = getString(R.string.custom_ctrl_file_info_format, savedPathData, savedEnabledData, savedDisabledData)
            editPathData.setText(savedPathData)
            editEnabledData.setText(savedEnabledData)
            editDisabledData.setText(savedDisabledData)
            customPathData = savedPathData
            customEnabledData = savedEnabledData
            customDisabledData = savedDisabledData
        }

        editPathData.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                customPathData = s.toString()
            }

            override fun afterTextChanged(s: Editable?) {

            }
        })

        editEnabledData.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                customEnabledData = s.toString()
            }

            override fun afterTextChanged(s: Editable?) {

            }
        })

        editDisabledData.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                customDisabledData = s.toString()
            }

            override fun afterTextChanged(s: Editable?) {

            }
        })

        btnUpdateData.setOnClickListener {
            if (customPathData.isNullOrBlank() || customEnabledData.isNullOrBlank() || customDisabledData.isNullOrBlank()) {
                return@setOnClickListener
            }
            Utils.stopService(this)
            settings.edit {
                putString(Constants.SAVED_PATH_DATA, customPathData)
                putString(Constants.SAVED_ENABLED_DATA, customEnabledData)
                putString(Constants.SAVED_DISABLED_DATA, customDisabledData)
            }
            updatedDataText.text = getString(R.string.custom_ctrl_file_info_format, customPathData, customEnabledData, customDisabledData)
            Utils.startServiceIfLimitEnabled(this)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
