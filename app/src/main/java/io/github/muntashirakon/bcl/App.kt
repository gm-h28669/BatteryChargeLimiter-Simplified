package io.github.muntashirakon.bcl

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell

class App: Application() {
    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setTimeout(10))
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
