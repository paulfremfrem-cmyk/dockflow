package com.kinko.dockflow

import com.kinko.dockflow.hooks.FloatingNavHook
import com.kinko.dockflow.hooks.ThreadsHook
import com.kinko.dockflow.hooks.YoutubeFloatingNavHook
import com.kinko.dockflow.reels.ReelsHook
import com.kinko.dockflow.utils.PrefsHelper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            PACKAGE_INSTAGRAM -> handleInstagram(lpparam)
            PACKAGE_THREADS -> handleThreads(lpparam)
            PACKAGE_YOUTUBE -> YoutubeFloatingNavHook().init(lpparam.classLoader)
        }
    }

    private fun handleInstagram(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[InstaMod] Target process detected: ${lpparam.packageName}")

        try {
            PrefsHelper.init()
            val classLoader = lpparam.classLoader

            // 1. Floating Bottom Navigation Bar
            FloatingNavHook().init(classLoader)

            // 2. Reels plein écran façon igglass (UI au-dessus de la capsule,
            //    vidéo agrandie, flou compositeur sur la vidéo)
            ReelsHook().init(classLoader)

            XposedBridge.log("[InstaMod] All hooks initialized successfully for Instagram.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to initialize hooks: ${t.message}")
        }
    }

    private fun handleThreads(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[ThreadsMod] Target Threads process detected: ${lpparam.packageName}")

        try {
            PrefsHelper.init()
            val classLoader = lpparam.classLoader

            // Initialize Threads Hooks (Developer Options, Navigation, Media)
            ThreadsHook().init(classLoader)

            XposedBridge.log("[ThreadsMod] All hooks initialized successfully for Threads.")
        } catch (t: Throwable) {
            XposedBridge.log("[ThreadsMod] Failed to initialize Threads hooks: ${t.message}")
        }
    }

    companion object {
        const val PACKAGE_INSTAGRAM = "com.instagram.android"
        const val PACKAGE_THREADS = "com.instagram.barcelona"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"
    }
}
