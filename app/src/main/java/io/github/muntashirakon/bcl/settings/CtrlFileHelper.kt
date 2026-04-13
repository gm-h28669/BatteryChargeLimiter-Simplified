package io.github.muntashirakon.bcl.settings

import android.app.ProgressDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.muntashirakon.bcl.Utils

object CtrlFileHelper {

    fun validateFiles(context: Context, callback: Runnable?) {
        val handler = Handler(Looper.getMainLooper())
        val dialog = ProgressDialog(context)
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        dialog.setMessage("Checking control file data, please wait...")
        dialog.isIndeterminate = true
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        Utils.executor.submit {
            Utils.validateCtrlFiles(context)
            handler.post {
                dialog.dismiss()
                callback?.run()
            }
        }
    }

}
