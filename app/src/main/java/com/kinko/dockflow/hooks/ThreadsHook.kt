package com.kinko.dockflow.hooks

import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import com.kinko.dockflow.utils.PrefsHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

class ThreadsHook {

    private var activeActivityRef: WeakReference<Activity>? = null
    private val isApplyingPill = ThreadLocal<Boolean>()

    fun init(classLoader: ClassLoader) {
        XposedBridge.log("[ThreadsMod] Initializing Threads hooks...")

        hookBarcelonaActivity(classLoader)

        if (PrefsHelper.isThreadsFloatingNavEnabled()) {
            hookFloatingNavBar(classLoader)
        }
    }

    private fun hookBarcelonaActivity(classLoader: ClassLoader) {
        try {
            val activityClass = XposedHelpers.findClass(
                "com.instagram.barcelona.mainactivity.BarcelonaActivity",
                classLoader
            )

            val activityHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    activeActivityRef = WeakReference(activity)
                }
            }

            XposedHelpers.findAndHookMethod(activityClass, "onCreate", Bundle::class.java, activityHook)
            XposedHelpers.findAndHookMethod(activityClass, "onResume", activityHook)
        } catch (t: Throwable) {
            XposedBridge.log("[ThreadsMod] Could not hook BarcelonaActivity: ${t.message}")
        }
    }

    private fun isNightMode(): Boolean {
        try {
            val activity = activeActivityRef?.get()
            if (activity != null) {
                return (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val sysUiMode = Resources.getSystem().configuration.uiMode
            return (sysUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            return false
        }
    }

    private fun hookFloatingNavBar(classLoader: ClassLoader) {
        XposedBridge.log("[ThreadsMod] Setting up Floating Bottom Navigation Bar for Threads...")

        try {
            val c0vWClass = XposedHelpers.findClassIfExists("X.0vW", classLoader)
            val c0AqClass = XposedHelpers.findClassIfExists("X.0Aq", classLoader)
            val c1AiClass = XposedHelpers.findClassIfExists("X.1Ai", classLoader)
            val c0B3Class = XposedHelpers.findClassIfExists("X.0B3", classLoader)
            val c0B4Class = XposedHelpers.findClassIfExists("X.0B4", classLoader)
            val c02FClass = XposedHelpers.findClassIfExists("X.02F", classLoader)
            val c1AlClass = XposedHelpers.findClassIfExists("X.1Al", classLoader)
            val c3bEClass = XposedHelpers.findClassIfExists("X.3bE", classLoader)

            XposedBridge.log(
                "[ThreadsMod] Classes found - 0vW: ${c0vWClass != null}, 0Aq: ${c0AqClass != null}, " +
                        "1Ai: ${c1AiClass != null}, 0B3: ${c0B3Class != null}, 0B4: ${c0B4Class != null}, " +
                        "02F: ${c02FClass != null}, 1Al: ${c1AlClass != null}, 3bE: ${c3bEClass != null}"
            )

            // 1. Hook X.0vW.A00 (navigationBarsPaddingIgnoringVisibility)
            // Bypass for the bottom bar container to avoid edge stretching,
            // but preserve for the floating '+' action button (BcnFloatingActionButton / 3bE)!
            if (c0vWClass != null) {
                for (method in c0vWClass.declaredMethods) {
                    if (method.name == "A00" && method.parameterTypes.size == 2) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val stack = Thread.currentThread().stackTrace
                                    val isFab = stack.any {
                                        it.className.contains("3bE") ||
                                                it.fileName?.contains("FloatingActionButton") == true
                                    }
                                    if (isFab) {
                                        return
                                    }

                                    val isTabsScreen = stack.any { element ->
                                        val cls = element.className
                                        val file = element.fileName ?: ""
                                        cls == "X.0vT" || cls.contains("BarcelonaMainTabs") ||
                                                file.contains("BarcelonaMainTabsScreen")
                                    }

                                    if (isTabsScreen) {
                                        param.result = param.args[1]
                                        XposedBridge.log("[ThreadsMod] Bypassed navigationBars padding in X.0vW.A00 for bottom bar")
                                    }
                                } catch (t: Throwable) {
                                    XposedBridge.log("[ThreadsMod] Error in 0vW.A00 hook: ${t.message}")
                                }
                            }
                        })
                    }
                }
            }

            // 2. Hook X.0Aq.A01 (Modifier.background)
            // Neutralize the solid white dock container background in 0vT,
            // while giving the '+' FAB the matching sleek island background color!
            if (c0AqClass != null) {
                for (method in c0AqClass.declaredMethods) {
                    if (method.name == "A01" && method.parameterTypes.size == 3) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (isApplyingPill.get() == true) {
                                        return
                                    }

                                    val stack = Thread.currentThread().stackTrace

                                    // If called from BcnFloatingActionButton (3bE), style the '+' FAB cleanly!
                                    val isFab = stack.any {
                                        it.className.contains("3bE") ||
                                                it.fileName?.contains("FloatingActionButton") == true
                                    }
                                    if (isFab) {
                                        val isNight = isNightMode()
                                        val argbColor = if (isNight) 0xEE1E1E1EL else 0xF0EAECEFL
                                        param.args[2] = (argbColor and 0xFFFFFFFFL) shl 32
                                        XposedBridge.log("[ThreadsMod] Styled '+' FAB background in 3bE!")
                                        return
                                    }

                                    val isTabsScreen = stack.any { element ->
                                        val cls = element.className
                                        val file = element.fileName ?: ""
                                        cls == "X.0vT" || cls.contains("BarcelonaMainTabs") ||
                                                file.contains("BarcelonaMainTabsScreen")
                                    }

                                    if (isTabsScreen) {
                                        param.result = param.args[0]
                                        XposedBridge.log("[ThreadsMod] Neutralized solid container background in 0Aq.A01!")
                                    }
                                } catch (t: Throwable) {
                                    XposedBridge.log("[ThreadsMod] Error in 0Aq.A01 hook: ${t.message}")
                                }
                            }
                        })
                    }
                }
            }

            // 3. Hook BcnFloatingActionButton (X.3bE.A00)
            // The '+' FAB MUST ONLY appear when the navigation bar is hidden on scroll!
            // When the bar is visible (f < 0.85), collapse alpha & scale to 0.0 so it is completely hidden.
            if (c3bEClass != null && c0B4Class != null) {
                for (method in c3bEClass.declaredMethods) {
                    if (method.name == "A00" && method.parameterTypes.size == 6) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val origModifier = param.args[1] ?: return
                                    val f = (param.args[3] as? Number)?.toFloat() ?: 0.0f

                                    // f = 0.0 when bottom bar is fully visible, f = 1.0 when bottom bar is fully hidden.
                                    // The '+' FAB must ONLY appear when the bar is hidden (f >= 0.85):
                                    val isHidden = f >= 0.85f
                                    val alpha = if (isHidden) ((f - 0.85f) / 0.15f).coerceIn(0.0f, 1.0f) else 0.0f

                                    // Bitmask 520184 sets bit 2048 (RectangleShape for non-null shape)
                                    // and clears bits 1, 2, 4 so scaleX, scaleY, and alpha follow our parameters:
                                    val hiddenOrVisibleModifier = XposedHelpers.callStaticMethod(
                                        c0B4Class, "A03",
                                        origModifier, null,
                                        alpha, alpha, alpha, // scaleX, scaleY, alpha
                                        0f, 0f, 0f, 0f, 0, 520184, 0L, false
                                    )
                                    param.args[1] = hiddenOrVisibleModifier
                                    XposedBridge.log("[ThreadsMod] '+' FAB visibility: alpha=$alpha (scroll f=$f)")
                                } catch (t: Throwable) {
                                    XposedBridge.log("[ThreadsMod] Error in 3bE.A00 hook: ${t.message}")
                                }
                            }
                        })
                    }
                }
            }

            // 4. Hook BdsTabBar (X.1Al.A06) - Style floating pill navbar across ALL tabs
            // Raised higher to bottom=22dp as requested by user ("releves un peu la barre flotante")!
            if (c1AlClass != null && c1AiClass != null && c02FClass != null && c0AqClass != null && c0B3Class != null) {
                for (method in c1AlClass.declaredMethods) {
                    if (method.name == "A06") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val origModifier = param.args[1] ?: return

                                    // 1. Detach from edges: 16dp horizontal, 22dp bottom (raised higher off the bottom!)
                                    val paddedModifier = XposedHelpers.callStaticMethod(
                                        c1AiClass, "A0I", origModifier, 16f, 0f, 16f, 22f
                                    )

                                    // 2. Pill shape: 26dp rounded corner radius
                                    val pillShape = XposedHelpers.callStaticMethod(c02FClass, "A04", 26f)

                                    // 3. Instagram-like color:
                                    // Light: #F0EAECEF (refined soft contrast off-white/light grey)
                                    // Dark: #EE1E1E1E (sleek dark graphite island)
                                    val isNight = isNightMode()
                                    val argbColor = if (isNight) 0xEE1E1E1EL else 0xF0EAECEFL
                                    val colorLong = (argbColor and 0xFFFFFFFFL) shl 32

                                    // 4. Background with rounded pill (flagged so 0Aq.A01 hook allows it)
                                    isApplyingPill.set(true)
                                    val bgModifier = try {
                                        XposedHelpers.callStaticMethod(
                                            c0AqClass, "A01", paddedModifier, pillShape, colorLong
                                        )
                                    } finally {
                                        isApplyingPill.set(false)
                                    }

                                    // 5. Clip pill contents to rounded shape
                                    val clippedModifier = XposedHelpers.callStaticMethod(
                                        c0B3Class, "A01", bgModifier, pillShape
                                    )

                                    param.args[1] = clippedModifier
                                    XposedBridge.log("[ThreadsMod] Successfully applied floating pill to BdsTabBar (X.1Al.A06) at bottom=22dp!")
                                } catch (t: Throwable) {
                                    XposedBridge.log("[ThreadsMod] Error in BdsTabBar styling: ${t.message}")
                                }
                            }
                        })
                    }
                }
            }

        } catch (t: Throwable) {
            XposedBridge.log("[ThreadsMod] Failed to hook Threads floating navbar: ${t.message}")
        }
    }
}
