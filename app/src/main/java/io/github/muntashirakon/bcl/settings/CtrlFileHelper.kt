package io.github.muntashirakon.bcl.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils

object CtrlFileHelper {

    fun validateFiles(context: Context, callback: Runnable?) {
        if (Utils.areCtrlFilesChecked(context)) {
            // we already have the files checked, just call the callback
            callback?.run()
            return
        }

        val handler = Handler(Looper.getMainLooper())

        // create a ProgressBar (Spinner) programmatically
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyle).apply {
            isIndeterminate = true
        }

        // Wrap it in a FrameLayout to center it
        val container = FrameLayout(context).apply {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            addView(progressBar, params)
            setPadding(0, 24, 0, 24)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.please_wait)
            .setMessage(R.string.check_control_in_progress)
            .setView(container) // Adds the centered spinner below the message
            .setCancelable(false)
            .create()

        dialog.show()

        Utils.executor.submit {
            val startTime = System.currentTimeMillis()
            Utils.validateCtrlFiles(context)
            val endTime = System.currentTimeMillis()

            // Ensure the dialog is visible for at least 1000ms
            val delay = 1000 - (endTime - startTime)
            if (delay > 0) {
                Thread.sleep(delay)
            }

            handler.post {
                dialog.dismiss()
                callback?.run()
            }
        }
    }

}
