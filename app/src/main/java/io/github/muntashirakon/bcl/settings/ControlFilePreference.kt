package io.github.muntashirakon.bcl.settings

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.Keep
import androidx.preference.DialogPreference

@Keep
class ControlFilePreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {

    private var controlFile: String? = null

    init {
        positiveButtonText = null
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        controlFile = getPersistedString(null)
    }

    fun getCurrentControlFile(): String? {
        return controlFile
    }

    fun callNotifyChanged() {
        onSetInitialValue(null)
        notifyChanged()
    }
}
