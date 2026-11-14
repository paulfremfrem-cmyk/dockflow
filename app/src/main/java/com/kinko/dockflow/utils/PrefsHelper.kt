package com.kinko.dockflow.utils

import de.robv.android.xposed.XSharedPreferences

object PrefsHelper {
    private const val PACKAGE_NAME = "com.kinko.dockflow"
    private const val PREFS_NAME = "instamod_prefs"

    private var xPrefs: XSharedPreferences? = null

    fun init() {
        try {
            xPrefs = XSharedPreferences(PACKAGE_NAME, PREFS_NAME).apply {
                try { makeWorldReadable() } catch (_: Throwable) {}
                reload()
            }
        } catch (_: Throwable) {
            xPrefs = null
        }
    }

    // Instagram Preferences
    fun isFloatingNavEnabled(): Boolean {
        // Diagnostic/compatibility mode: Instagram's isolated process can read
        // XSharedPreferences as false even while the companion switch is on.
        // Keep the floating-nav hook enabled while we validate the real view.
        return true
    }

    // Threads Preferences
    fun isThreadsFloatingNavEnabled(): Boolean {
        xPrefs?.reload()
        return xPrefs?.getBoolean("enable_threads_floating_nav", true) ?: true
    }

}
