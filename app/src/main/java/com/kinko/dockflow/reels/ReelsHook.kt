package com.kinko.dockflow.reels

import android.app.Activity
import android.view.SurfaceView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Point d'entrée du traitement Reels façon igglass.
 *
 * Les hooks s'ancrent sur des classes du système (Activity, SurfaceView),
 * jamais sur des classes ofusquées d'Instagram.
 */
class ReelsHook {

    fun init(classLoader: ClassLoader) {
        ReelsLog.i("initialisation")
        ReelsLog.safe("hook SurfaceView") { hookSurfaceViews() }
        ReelsLog.safe("hook Activity.onResume") { hookOnResume() }
    }

    /** Tient la liste des SurfaceView (vidéos) pour ReelsVideoBlur. */
    private fun hookSurfaceViews() {
        XposedHelpers.findAndHookMethod(
            SurfaceView::class.java, "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    ReelsLog.safe("SurfaceView attachée") {
                        ReelsVideoBlur.Registry.attached(param.thisObject as SurfaceView)
                    }
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            SurfaceView::class.java, "onDetachedFromWindow",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    ReelsLog.safe("SurfaceView détachée") {
                        ReelsVideoBlur.Registry.detached(param.thisObject as SurfaceView)
                    }
                }
            },
        )
        ReelsLog.i("hooks SurfaceView installés")
    }

    /** À chaque reprise d'Activity, on tente d'attacher le traitement Reels. */
    private fun hookOnResume() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    ReelsLog.safe("onResume") {
                        val activity = param.thisObject as Activity
                        val decor = activity.window?.decorView ?: return@safe
                        decor.post { ReelsLog.safe("attach Reels") { ReelsController.attach(activity) } }
                    }
                }
            },
        )
        ReelsLog.i("hook Activity.onResume installé")
    }
}
