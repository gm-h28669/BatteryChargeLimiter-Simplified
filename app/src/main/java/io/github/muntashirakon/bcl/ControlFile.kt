package io.github.muntashirakon.bcl

import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.Shell

/**
 * Created by Michael on 31.03.2017.
 *
 * This is a pojo class representing known control files.
 * The private members are populated by the GSON library.
 * The "Keep" annotation makes ProGuard ignore those fields instead of optimizing them away.
 */
class ControlFile {

    @Keep
    val file: String? = null
    @Keep
    val label: String? = null
    @Keep
    val details: String? = null
    @Keep
    val chargeOn: String? = null
    @Keep
    val chargeOff: String? = null
    @Keep
    val experimental: Boolean? = false
    @Keep
    val order: Int? = 0
    @Keep
    val issues: Boolean? = false
    @Transient
    private var checked = false
    @Transient
    private var valid = false

    val isValid: Boolean
        get() {
            if (!checked) {
                throw RuntimeException("Tried to check unvalidated ControlFile")
            }
            return valid
        }

    @WorkerThread
    fun validate() {
        if (!checked) {
            // emulate that device contains the file in emulator
            // this allows running app in emulator without file existing in file system
            valid = if (Utils.isEmulator())
                true
            else
                Shell.cmd("test -e ${file!!}").exec().isSuccess
            checked = true
        }
    }
}
