package io.github.muntashirakon.bcl

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputEditText

class TextInputTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {
    init {
        keyListener = null
    }
}