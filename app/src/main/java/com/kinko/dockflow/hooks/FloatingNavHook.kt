package com.kinko.dockflow.hooks

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.kinko.dockflow.utils.PrefsHelper
import com.kinko.dockflow.utils.ViewUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap

class FloatingNavHook {

    private var activeActivityRef: WeakReference<Activity>? = null
    private var customNavBarRef: WeakReference<CustomNavBarView>? = null
    private var customScrubberRef: WeakReference<CustomReelsScrubberView>? = null
    @Volatile private var activeScrubberRef: WeakReference<SeekBar>? = null
    @Volatile private var nativeScrubberClass: Class<*>? = null
    private var isShrunk = false
    private var isWindowTouchHooked = false
    private val nativeNavNames = arrayOf("feed_tab", "clips_tab", "search_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab")

    private fun dispatchToNativeScrubber(sb: SeekBar, action: Int, fraction: Float) {
        val w = if (sb.width > 0) sb.width.toFloat() else 1080f
        val x = (fraction * w).coerceIn(0f, w)
        val y = sb.height / 2f
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            sb.dispatchTouchEvent(event)
        } catch (_: Throwable) {
        } finally {
            event.recycle()
        }

        val max = sb.max
        if (max > 0) {
            val targetMs = (fraction * max).toInt().coerceIn(0, max)
            sb.progress = targetMs
            try {
                val listener = try {
                    (XposedHelpers.getObjectField(sb, "mOnSeekBarChangeListener") ?: XposedHelpers.getObjectField(sb, "A01")) as? SeekBar.OnSeekBarChangeListener
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.getObjectField(sb, "A01") as? SeekBar.OnSeekBarChangeListener
                    } catch (_: Throwable) { null }
                }
                if (action == MotionEvent.ACTION_DOWN) listener?.onStartTrackingTouch(sb)
                listener?.onProgressChanged(sb, targetMs, true)
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) listener?.onStopTrackingTouch(sb)
            } catch (_: Throwable) {}
        }
    }

    private val visibleBoundsRect = Rect()

    private fun findActiveNativeScrubber(root: View?): SeekBar? {
        if (root == null) return null
        val fallback = arrayOfNulls<SeekBar>(1)
        return findScrubber(root, fallback) ?: fallback[0]
    }

    private fun findScrubber(root: View, fallback: Array<SeekBar?>): SeekBar? {
        if (root is SeekBar && ((scrubberId != 0 && root.id == scrubberId) || root.javaClass.name.contains("VideoScrubberSeekBar"))) {
            if (root.isShown && root.width > 0 && root.height > 0 && root.isAttachedToWindow) {
                if (root.getGlobalVisibleRect(visibleBoundsRect) && visibleBoundsRect.bottom > 0) {
                    return root
                }
                if (fallback[0] == null) fallback[0] = root
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findScrubber(root.getChildAt(i), fallback)
                if (found != null) return found
            }
        }
        return null
    }

    fun init(classLoader: ClassLoader) {
        try {
            val activityHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val className = activity.javaClass.name
                    if (className.contains("InstagramMainActivity") ||
                        className.contains("MainActivity") ||
                        className.contains("MainTabActivity")
                    ) {
                        activeActivityRef = WeakReference(activity)
                        setupEdgeToEdge(activity)
                        hookMainBottomNavigation(activity)
                        hookWindowTouchForScroll(activity)
                    }
                }
            }

            // Hook Activity.onCreate & onResume
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onCreate",
                Bundle::class.java,
                activityHook
            )
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onResume",
                activityHook
            )

            hookTargetedOptimizations(classLoader)
            hookStoryViewer(classLoader)

            try {
                val scrubberClass = XposedHelpers.findClassIfExists("com.instagram.ui.mediaactions.VideoScrubberSeekBar", classLoader)
                if (scrubberClass != null) {
                    val methods = scrubberClass.declaredMethods.map { it.name }.distinct()
                    val fields = scrubberClass.declaredFields.map { "${it.name}:${it.type.simpleName}" }
                    XposedBridge.log("[InstaMod] VideoScrubberSeekBar methods: $methods")
                    XposedBridge.log("[InstaMod] VideoScrubberSeekBar fields: $fields")

                    XposedHelpers.findAndHookMethod(
                        scrubberClass,
                        "onProgressChanged",
                        SeekBar::class.java,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val sb = param.args[0] as? SeekBar ?: return
                                val p = param.args[1] as Int
                                val fromUser = param.args[2] as Boolean
                                if (!fromUser) {
                                    activeScrubberRef = WeakReference(sb)
                                    customScrubberRef?.get()?.updatePlaybackProgress(p, sb.max)
                                }
                            }
                        }
                    )
                    XposedBridge.log("[InstaMod] VideoScrubberSeekBar onProgressChanged hook installed.")
                }
            } catch (t: Throwable) {
                XposedBridge.log("[InstaMod] Scrubber hook error: ${t.message}")
            }

            try {
                val progressHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pb = param.thisObject as? ProgressBar ?: return
                        val id = pb.id
                        if ((scrubberId != 0 && id == scrubberId) || pb.javaClass.name.contains("VideoScrubberSeekBar")) {
                            val p = param.args[0] as Int
                            val max = pb.max
                            if (max > 0) {
                                (pb as? SeekBar)?.let { activeScrubberRef = WeakReference(it) }
                                customScrubberRef?.get()?.updatePlaybackProgress(p, max)
                            }
                        }
                    }
                }

                XposedHelpers.findAndHookMethod(
                    ProgressBar::class.java,
                    "setProgress",
                    Int::class.javaPrimitiveType,
                    progressHook
                )
                try {
                    XposedHelpers.findAndHookMethod(
                        ProgressBar::class.java,
                        "setProgress",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        progressHook
                    )
                } catch (_: Throwable) {}

                XposedHelpers.findAndHookMethod(
                    ProgressBar::class.java,
                    "setMax",
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val pb = param.thisObject as? ProgressBar ?: return
                            val id = pb.id
                            if ((scrubberId != 0 && id == scrubberId) || pb.javaClass.name.contains("VideoScrubberSeekBar")) {
                                val max = param.args[0] as Int
                                if (max > 0) {
                                    (pb as? SeekBar)?.let { activeScrubberRef = WeakReference(it) }
                                    customScrubberRef?.get()?.setTotalDuration(max)
                                }
                            }
                        }
                    }
                )
                XposedBridge.log("[InstaMod] ProgressBar setProgress/setMax hooks installed.")
            } catch (t: Throwable) {
                XposedBridge.log("[InstaMod] Failed to hook ProgressBar: ${t.message}")
            }

            XposedBridge.log("[InstaMod] FloatingNavHook (Ultra High Performance) initialized successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to hook FloatingNav: ${t.message}")
        }
    }

    private fun setupEdgeToEdge(activity: Activity) {
        try {
            val window = activity.window ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            XposedBridge.log("[InstaMod] Configured transparent navigation bar for edge-to-edge content.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Error setting edge-to-edge: ${t.message}")
        }
    }

    @Volatile private var targetViewIds: IntArray = IntArray(0)
    @Volatile private var tabBarId = 0
    @Volatile private var tabBarShadowId = 0
    @Volatile private var tabBarBackgroundId = 0
    @Volatile private var clipsMediaInfoId = 0
    @Volatile private var clipsUfiId = 0
    @Volatile private var clipsGradientId = 0
    @Volatile private var clipsFooterMaskId = 0
    @Volatile private var clipsTouchableBgId = 0
    @Volatile private var scrubberId = 0

    private fun resolveTargetIds(res: android.content.res.Resources, pkg: String) {
        if (targetViewIds.isNotEmpty()) return
        val names = arrayOf(
            "swipeable_tab_view_pager",
            "swipeable_nav_view_pager",
            "layout_container_main",
            "layout_container_swipeable",
            "swipeable_nav_view_pager_inner_recycler_view",
            "layout_container_main_wrapper",
            "layout_container_main_panel",
            "layout_container_center_right_coordinator_layout",
            "layout_container",
            "gesture_manager",
            "clips_viewer_container",
            "clips_viewer_view_pager"
        )
        val list = mutableListOf<Int>()
        for (n in names) {
            val id = res.getIdentifier(n, "id", pkg)
            if (id != 0) list.add(id)
        }
        targetViewIds = list.toIntArray()
        tabBarId = res.getIdentifier("tab_bar", "id", pkg)
        tabBarShadowId = res.getIdentifier("tab_bar_shadow", "id", pkg)
        tabBarBackgroundId = res.getIdentifier("tab_bar_background", "id", pkg)

        clipsMediaInfoId = res.getIdentifier("clips_media_info_component", "id", pkg)
        clipsUfiId = res.getIdentifier("clips_ufi_component", "id", pkg)
        clipsGradientId = res.getIdentifier("clips_bottom_legibility_gradient_component", "id", pkg)
        clipsFooterMaskId = res.getIdentifier("clips_footer_mask_container", "id", pkg)
        clipsTouchableBgId = res.getIdentifier("clips_touchable_background", "id", pkg)
        scrubberId = res.getIdentifier("scrubber", "id", pkg)

        XposedBridge.log("[InstaMod] Pre-resolved ${targetViewIds.size} target IDs, tabBarId=$tabBarId, clipsMediaInfoId=$clipsMediaInfoId, clipsUfiId=$clipsUfiId, clipsGradientId=$clipsGradientId, clipsFooterMaskId=$clipsFooterMaskId, scrubberId=$scrubberId")
    }

    private fun isTargetId(id: Int): Boolean {
        if (id <= 0) return false
        val ids = targetViewIds
        for (i in ids.indices) {
            if (id == ids[i]) return true
        }
        return false
    }

    private fun hookTargetedOptimizations(classLoader: ClassLoader) {
        // Keep the frame hot path free of a global ViewGroup.drawChild hook. Native
        // elements are hidden once at layout time instead of checking every child draw.
        // 1. Synchronous 0ms full-height enforcement on View.layout
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "layout",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PrefsHelper.isFloatingNavEnabled()) return
                        val v = param.thisObject as? View ?: return
                        val id = v.id
                        if (id <= 0) return
                        if (targetViewIds.isEmpty()) {
                            resolveTargetIds(v.resources, v.context.packageName)
                        }
                        if (id == clipsFooterMaskId) {
                            if (v.visibility != View.GONE) {
                                v.visibility = View.GONE
                            }
                            return
                        }
                        if (isTargetId(id)) {
                            val parent = v.parent as? View ?: return
                            val pHeight = if (parent.height > 0) parent.height else parent.measuredHeight
                            if (pHeight > 0) {
                                val currentB = param.args[3] as Int
                                if (currentB < pHeight) {
                                    param.args[3] = pHeight
                                }
                            }
                            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                                if (it.bottomMargin != 0) it.bottomMargin = 0
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!PrefsHelper.isFloatingNavEnabled()) return
                        val v = param.thisObject as? View ?: return
                        val id = v.id
                        if (id > 0 && (id == tabBarId || id == tabBarBackgroundId || id == tabBarShadowId)) {
                            if (v.alpha != 0f) v.alpha = 0f
                        }
                        if (id > 0 && (id == clipsMediaInfoId || id == clipsUfiId)) {
                            // Restore the earlier working footer placement: the custom slider
                            // owns its attachment and applies the same measured lift to each reel.
                            // ReelsController is not allowed to overwrite these translations.
                            CustomReelsScrubberView.registerFooter(v)
                            val lift = CustomReelsScrubberView.footerLiftPx
                            if (lift.isFinite() && kotlin.math.abs(v.translationY - lift) > 0.5f) {
                                v.translationY = lift
                            }
                        }
                        val scrubberClass = nativeScrubberClass
                        val isNativeScrubber = (scrubberId != 0 && id == scrubberId) ||
                            (scrubberClass != null && v.javaClass === scrubberClass) ||
                            (scrubberClass == null && v.javaClass.name.contains("VideoScrubberSeekBar"))
                        if (isNativeScrubber) {
                            nativeScrubberClass = v.javaClass
                            if (v.alpha != 0f) v.alpha = 0f
                            (v as? SeekBar)?.let { activeScrubberRef = WeakReference(it) }
                        }
                    }
                }
            )
            XposedBridge.log("[InstaMod] High-speed View.layout synchronous expansion hook installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to hook View.layout: ${t.message}")
        }

        // 3. Prevent height deduction in ViewGroup.measureChildWithMargins
        try {
            XposedHelpers.findAndHookMethod(
                ViewGroup::class.java,
                "measureChildWithMargins",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PrefsHelper.isFloatingNavEnabled()) return
                        val child = param.args[0] as? View ?: return
                        val id = child.id
                        if (id <= 0) return
                        if (targetViewIds.isEmpty()) {
                            resolveTargetIds(child.resources, child.context.packageName)
                        }
                        if (id == clipsFooterMaskId) {
                            if (child.visibility != View.GONE) {
                                child.visibility = View.GONE
                            }
                            return
                        }
                        if (isTargetId(id)) {
                            (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                                if (it.bottomMargin != 0) it.bottomMargin = 0
                            }
                            val heightUsed = param.args[4] as? Int ?: 0
                            if (heightUsed > 0) {
                                param.args[4] = 0
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[InstaMod] High-speed measureChildWithMargins hook installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to hook measureChildWithMargins: ${t.message}")
        }

        // 4. Force MATCH_PARENT and bottomMargin = 0 on View.setLayoutParams
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setLayoutParams",
                ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PrefsHelper.isFloatingNavEnabled()) return
                        val v = param.thisObject as? View ?: return
                        val id = v.id
                        if (id <= 0) return
                        if (targetViewIds.isEmpty()) {
                            resolveTargetIds(v.resources, v.context.packageName)
                        }
                        if (id == clipsFooterMaskId) {
                            if (v.visibility != View.GONE) {
                                v.visibility = View.GONE
                            }
                            return
                        }
                        if (isTargetId(id)) {
                            val lp = param.args[0] as? ViewGroup.MarginLayoutParams ?: return
                            if (lp.bottomMargin != 0 || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                                lp.bottomMargin = 0
                                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[InstaMod] High-speed View.setLayoutParams hook installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to hook View.setLayoutParams: ${t.message}")
        }
    }

    private fun hookStoryViewer(classLoader: ClassLoader) {
        try {
            val instantHideHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    customNavBarRef?.get()?.setHiddenByOverlay(true)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    customNavBarRef?.get()?.setHiddenByOverlay(true)
                }
            }

            val restoreHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    customNavBarRef?.get()?.setHiddenByOverlay(false)
                }
            }

            // Stories: ReelViewerFragment lifecycle hooks
            val reelViewerClass = XposedHelpers.findClassIfExists(
                "instagram.features.stories.fragment.ReelViewerFragment",
                classLoader
            )
            if (reelViewerClass != null) {
                for (m in arrayOf("onAttach", "onCreate", "onCreateView", "onViewCreated", "onStart", "onResume")) {
                    try {
                        XposedHelpers.findAndHookMethod(reelViewerClass, m, instantHideHook)
                    } catch (_: Throwable) {}
                }
                for (m in arrayOf("onDestroyView", "onDestroy", "onDetach")) {
                    try {
                        XposedHelpers.findAndHookMethod(reelViewerClass, m, restoreHook)
                    } catch (_: Throwable) {}
                }
                XposedBridge.log("[InstaMod] ReelViewerFragment instant lifecycle hooks installed.")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to hook Story viewer: ${t.message}")
        }
    }

    private class WindowCallbackWrapper(
        private val delegate: Window.Callback,
        private val onTouchEvent: (MotionEvent) -> Unit
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null) {
                onTouchEvent(event)
            }
            return delegate.dispatchTouchEvent(event)
        }
    }

    private var touchStartY = 0f
    private var touchLastY = 0f

    private fun hookWindowTouchForScroll(activity: Activity) {
        if (isWindowTouchHooked) return
        try {
            val window = activity.window ?: return
            val originalCallback = window.callback ?: return

            window.callback = WindowCallbackWrapper(originalCallback) { event ->
                if (!PrefsHelper.isFloatingNavEnabled()) return@WindowCallbackWrapper
                val bar = customNavBarRef?.get() ?: return@WindowCallbackWrapper
                if (bar.visibility != View.VISIBLE || bar.alpha <= 0.05f) return@WindowCallbackWrapper

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.y
                        touchLastY = event.y
                        bar.setScrolling(false)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bar.setScrolling(true)
                        val deltaY = event.y - touchLastY
                        val totalDy = event.y - touchStartY
                        touchLastY = event.y

                        if (bar.isReels()) {
                            if (isShrunk) bar.post { expandTabBar(bar) }
                            return@WindowCallbackWrapper
                        }

                        val barTop = bar.top
                        if (barTop > 0 && event.y >= barTop - 20) return@WindowCallbackWrapper

                        if (deltaY < -12 && totalDy < -30) {
                            if (!isShrunk) bar.post { shrinkTabBar(bar) }
                        } else if (deltaY > 12 && totalDy > 30) {
                            if (isShrunk) bar.post { expandTabBar(bar) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        bar.setScrolling(false)
                    }
                }
            }
            isWindowTouchHooked = true
            XposedBridge.log("[InstaMod] Fast direct WindowCallbackWrapper installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to hook Window touch: ${t.message}")
        }
    }

    private fun shrinkTabBar(navView: View) {
        if (isShrunk) return
        isShrunk = true

        // Vertical center pivot: shrinking scales inwards and NEVER descends towards home pill!
        navView.pivotX = navView.width / 2f
        navView.pivotY = navView.height / 2f

        navView.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .translationY(0f)
            .alpha(0.95f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun expandTabBar(navView: View) {
        if (!isShrunk) return
        isShrunk = false

        navView.pivotX = navView.width / 2f
        navView.pivotY = navView.height / 2f

        navView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationY(0f)
            .alpha(1.0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private var isStoryObserverSetup = false

    private fun setupStoryAndOverlayDetection(activity: Activity) {
        if (isStoryObserverSetup) return
        try {
            val getSupportFragmentManagerMethod = activity.javaClass.getMethod("getSupportFragmentManager")
            val fm = getSupportFragmentManagerMethod.invoke(activity) ?: return

            val updateVisibility: () -> Unit = {
                val bar = customNavBarRef?.get()
                if (bar != null) {
                    var isStoryOrFullScreen = false
                    try {
                        val count = XposedHelpers.callMethod(fm, "getBackStackEntryCount") as? Int ?: 0
                        for (i in 0 until count) {
                            val entry = XposedHelpers.callMethod(fm, "getBackStackEntryAt", i)
                            val name = XposedHelpers.callMethod(entry, "getName") as? String ?: ""
                            if (name.contains("ReelViewer") || name.contains("Story")) {
                                isStoryOrFullScreen = true
                                break
                            }
                        }
                        if (!isStoryOrFullScreen) {
                            val fragments = XposedHelpers.callMethod(fm, "getFragments") as? List<*> ?: emptyList<Any>()
                            for (f in fragments) {
                                if (f != null) {
                                    val isResumed = XposedHelpers.callMethod(f, "isResumed") as? Boolean ?: false
                                    val isHidden = XposedHelpers.callMethod(f, "isHidden") as? Boolean ?: false
                                    if (isResumed && !isHidden) {
                                        val cName = f.javaClass.name
                                        if (cName.contains("ReelViewerFragment") ||
                                            cName.contains("StoryViewer")
                                        ) {
                                            isStoryOrFullScreen = true
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    bar.setHiddenByOverlay(isStoryOrFullScreen)
                    customScrubberRef?.get()?.setOverlayHidden(isStoryOrFullScreen)
                }
            }

            val backStackListenerClass = XposedHelpers.findClass(
                "androidx.fragment.app.FragmentManager\$OnBackStackChangedListener",
                activity.classLoader
            )
            val backStackProxy = java.lang.reflect.Proxy.newProxyInstance(
                activity.classLoader,
                arrayOf(backStackListenerClass)
            ) { _, method, _ ->
                if (method.name == "onBackStackChanged") {
                    updateVisibility()
                }
                null
            }
            XposedHelpers.callMethod(fm, "addOnBackStackChangedListener", backStackProxy)
            isStoryObserverSetup = true
            XposedBridge.log("[InstaMod] OnBackStackChangedListener proxy installed for Stories.")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] Failed to setup OnBackStackChangedListener: ${t.message}")
        }
    }

    private var mainPagerRef: WeakReference<View>? = null

    // Instagram's classes must be matched by name: this module ships its own androidx copy, so
    // `is ViewPager2` is always false here (different ClassLoader, same class name).
    private fun isPager2View(v: View): Boolean = v.javaClass.name.contains("ViewPager2")

    private fun isPager1View(v: View): Boolean =
        v.javaClass.name.contains("viewpager.widget.ViewPager")

    private fun pagerItemCount(v: View): Int = try {
        val adapter = XposedHelpers.callMethod(v, "getAdapter")
        when {
            adapter == null -> -1
            isPager2View(v) -> (XposedHelpers.callMethod(adapter, "getItemCount") as? Int) ?: -1
            else -> (XposedHelpers.callMethod(adapter, "getCount") as? Int) ?: -1
        }
    } catch (_: Throwable) {
        -1
    }

    private fun pagerIsHorizontal(v: View): Boolean = try {
        !isPager2View(v) || (XposedHelpers.callMethod(v, "getOrientation") as? Int ?: 1) == 0
    } catch (_: Throwable) {
        true
    }

    /**
     * Finds the horizontal pager that hosts the five main tabs (Home, Reels, Search,
     * Activity, Profile). Instagram gives it a stable id in current builds; otherwise we take
     * the largest horizontal pager holding about five pages.
     */
    private fun findMainTabPager(root: View): View? {
        for (name in arrayOf("swipeable_tab_view_pager", "swipeable_nav_view_pager")) {
            val id = root.context.resources.getIdentifier(name, "id", root.context.packageName)
            if (id == 0) continue
            val view = root.findViewById<View>(id) ?: continue
            if (isPager2View(view) || isPager1View(view)) return view
        }
        var best: View? = null
        var bestArea = -1
        fun walk(v: View) {
            if (v !is ViewGroup) return
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (isPager2View(child) || isPager1View(child)) {
                    val count = pagerItemCount(child)
                    if (pagerIsHorizontal(child) && count in 4..8) {
                        val area = child.width * child.height
                        if (area > bestArea) {
                            bestArea = area
                            best = child
                        }
                    }
                }
                walk(child)
            }
        }
        walk(root)
        return best
    }

    /**
     * Remembers the main tab pager so the bar can follow side swipes between sections (those
     * never touch the tab bar). The pager is created a bit after the bar, so retry briefly.
     */
    private fun watchMainTabPager(host: View, attempt: Int = 0) {
        val root = host.rootView as? ViewGroup
        val pager = if (root != null) findMainTabPager(root) else null
        if (pager == null) {
            if (attempt < 15) {
                host.postDelayed({ watchMainTabPager(host, attempt + 1) }, 500L)
            }
            return
        }
        mainPagerRef = WeakReference(pager)
        XposedBridge.log("[InstaMod] main tab pager hooked: ${pager.javaClass.simpleName}")
    }

    private fun currentMainPagerIndex(): Int? {
        val pager = mainPagerRef?.get() ?: return null
        return try {
            XposedHelpers.callMethod(pager, "getCurrentItem") as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookMainBottomNavigation(activity: Activity) {
        val decorView = activity.window.decorView as? ViewGroup ?: return
        decorView.post {
            try {
                if (!PrefsHelper.isFloatingNavEnabled()) return@post

                val originalBar = findOriginalTabBar(decorView) ?: return@post
                val parent = originalBar.parent as? ViewGroup ?: return@post

                setupRebuiltFloatingBar(parent, originalBar)
                setupStoryAndOverlayDetection(activity)
            } catch (t: Throwable) {
                XposedBridge.log("[InstaMod] Error setting up custom bar: ${t.message}")
            }
        }
    }

    private fun findOriginalTabBar(root: ViewGroup): ViewGroup? {
        val context = root.context
        val resId = context.resources.getIdentifier("tab_bar", "id", context.packageName)
        if (resId != 0) {
            val view = root.findViewById<View>(resId)
            if (view is ViewGroup) return view
        }
        return findViewByEntryName(root, "tab_bar") as? ViewGroup
    }

    private fun findViewByEntryName(root: ViewGroup, target: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.id != View.NO_ID) {
                try {
                    val entryName = child.resources.getResourceEntryName(child.id)
                    if (entryName.equals(target, ignoreCase = true) || entryName.contains(target, ignoreCase = true)) {
                        return child
                    }
                } catch (_: Throwable) {}
            }
            if (child is ViewGroup) {
                val found = findViewByEntryName(child, target)
                if (found != null) return found
            }
        }
        return null
    }

    private fun setupRebuiltFloatingBar(parent: ViewGroup, originalBar: ViewGroup) {
        val context = parent.context

        // 1. Remove shadow divider
        try {
            val shadowId = context.resources.getIdentifier("tab_bar_shadow", "id", context.packageName)
            if (shadowId != 0) {
                val shadow = parent.findViewById<View>(shadowId)
                if (shadow != null) parent.removeView(shadow)
            }
        } catch (_: Throwable) {}

        // 2. Keep Instagram's native bar in the hierarchy for reliable tab switching,
        //    but make it visually transparent behind the floating replacement.
        originalBar.setBackgroundColor(Color.TRANSPARENT)
        originalBar.background = null
        originalBar.setWillNotDraw(true)
        originalBar.visibility = View.VISIBLE
        originalBar.alpha = 0f
        hideOriginalBarVisuals(originalBar)
        val lp = originalBar.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.height = 0
            lp.setMargins(0, 0, 0, 0)
            originalBar.layoutParams = lp
        }

        // 3. Make parent container and ViewPager full screen edge-to-edge
        makeFullHeight(parent)
        val grandparent = parent.parent as? ViewGroup
        makeFullHeight(grandparent)
        expandContentViews(parent, originalBar)

        // 4. Bar geometry (Raised to 22dp, 50dp height for a slightly thicker bar)
        val barHeight = ViewUtils.dpToPx(context, 50f)
        val horizontalMargin = ViewUtils.dpToPx(context, 24f)
        val bottomMargin = ViewUtils.dpToPx(context, 22f)

        // 5. Scrubber geometry. Raise the slider and its attached footer slightly above
        // the navigation capsule so neither gets obscured by the floating bar.
        val scrubberHeight = ViewUtils.dpToPx(context, 48f)
        val scrubberHorizontalMargin = ViewUtils.dpToPx(context, 26f)
        val scrubberBottomMargin = ViewUtils.dpToPx(context, 82f)

        val handleTapReel: () -> Unit = {
            parent.post {
                val root = parent.rootView
                val now = android.os.SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 540f, 1000f, 0)
                val up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, 540f, 1000f, 0)
                try {
                    root.dispatchTouchEvent(down)
                    root.dispatchTouchEvent(up)
                } catch (_: Throwable) {
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
        }

        var customScrubber = parent.findViewWithTag<CustomReelsScrubberView>("instamod_custom_reels_scrubber")
        if (customScrubber == null) {
            customScrubber = CustomReelsScrubberView(context).apply {
                tag = "instamod_custom_reels_scrubber"
                nativeScrubberProvider = {
                    val ref = activeScrubberRef?.get()
                    ref?.takeIf { it.isShown && it.width > 0 && it.height > 0 && it.isAttachedToWindow }
                        ?: findActiveNativeScrubber(parent.rootView)
                }
                onTapReel = handleTapReel
                onSeekListener = { fraction, action ->
                    val nativeSb = nativeScrubberProvider?.invoke()
                    if (nativeSb != null) {
                        activeScrubberRef = WeakReference(nativeSb)
                        dispatchToNativeScrubber(nativeSb, action, fraction)
                    }
                }
            }
            val scrubberLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrubberHeight,
                Gravity.BOTTOM
            ).apply {
                setMargins(scrubberHorizontalMargin, 0, scrubberHorizontalMargin, scrubberBottomMargin)
            }
            parent.addView(customScrubber, scrubberLp)
            XposedBridge.log("[InstaMod] Custom Reels Scrubber added to layout.")
        } else {
            customScrubber.nativeScrubberProvider = {
                val ref = activeScrubberRef?.get()
                ref?.takeIf {
                    it.isShown && it.width > 0 && it.height > 0 &&
                        it.getGlobalVisibleRect(visibleBoundsRect)
                } ?: findActiveNativeScrubber(parent.rootView)
            }
            customScrubber.onTapReel = handleTapReel
            val slp = customScrubber.layoutParams as? FrameLayout.LayoutParams
            if (slp != null && (slp.height != scrubberHeight || slp.bottomMargin != scrubberBottomMargin || slp.leftMargin != scrubberHorizontalMargin)) {
                slp.height = scrubberHeight
                slp.bottomMargin = scrubberBottomMargin
                slp.leftMargin = scrubberHorizontalMargin
                slp.rightMargin = scrubberHorizontalMargin
                slp.gravity = Gravity.BOTTOM
                customScrubber.layoutParams = slp
            }
        }
        customScrubberRef = WeakReference(customScrubber)

        var customBar = parent.findViewWithTag<CustomNavBarView>("instamod_custom_floating_bar")
        if (customBar == null) {
            customBar = CustomNavBarView(
                context,
                originalBar,
                onExpandRequested = {
                    val b = customNavBarRef?.get() ?: return@CustomNavBarView
                    expandTabBar(b)
                },
                onTabClick = { tabIndex ->
                    expandContentViews(parent, originalBar)
                    // Forward click to original Instagram tab
                    if (tabIndex in 0 until originalBar.childCount) {
                        originalBar.getChildAt(tabIndex).performClick()
                    }
                },
                onReelsStateChanged = { isReels ->
                    customScrubberRef?.get()?.setReelsActive(isReels)
                },
                onOverlayHiddenChanged = { isHidden ->
                    customScrubberRef?.get()?.setOverlayHidden(isHidden)
                }
            )
            customBar.tag = "instamod_custom_floating_bar"

            val pagerNames = arrayOf("swipeable_tab_view_pager", "swipeable_nav_view_pager")
            for (pName in pagerNames) {
                val pId = context.resources.getIdentifier(pName, "id", context.packageName)
                if (pId != 0) {
                    val view = parent.findViewById<View>(pId)
                    if (view is androidx.viewpager.widget.ViewPager) {
                        view.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.OnPageChangeListener {
                            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
                            override fun onPageSelected(position: Int) {
                                XposedBridge.log("[InstaMod] onPageSelected position=$position")
                                expandContentViews(parent, originalBar)
                                customNavBarRef?.get()?.setSelectedTab(position, animate = true)
                            }
                            override fun onPageScrollStateChanged(state: Int) {
                                val scrolling = (state != androidx.viewpager.widget.ViewPager.SCROLL_STATE_IDLE)
                                customNavBarRef?.get()?.setScrolling(scrolling)
                            }
                        })
                    } else if (view is androidx.viewpager2.widget.ViewPager2) {
                        view.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
                            override fun onPageSelected(position: Int) {
                                XposedBridge.log("[InstaMod] onPageSelected position=$position")
                                expandContentViews(parent, originalBar)
                                customNavBarRef?.get()?.setSelectedTab(position, animate = true)
                            }
                            override fun onPageScrollStateChanged(state: Int) {
                                val scrolling = (state != androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE)
                                customNavBarRef?.get()?.setScrolling(scrolling)
                            }
                        })
                    }
                }
            }

            val customLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight,
                Gravity.BOTTOM
            ).apply {
                setMargins(horizontalMargin, 0, horizontalMargin, bottomMargin)
            }

            parent.addView(customBar, customLp)
            XposedBridge.log("[InstaMod] Rebuilt Custom Floating Bar added to layout.")
        } else {
            val clp = customBar.layoutParams as? FrameLayout.LayoutParams
            if (clp != null && (clp.height != barHeight || clp.bottomMargin != bottomMargin || clp.leftMargin != horizontalMargin)) {
                clp.height = barHeight
                clp.bottomMargin = bottomMargin
                clp.leftMargin = horizontalMargin
                clp.rightMargin = horizontalMargin
                clp.gravity = Gravity.BOTTOM
                customBar.layoutParams = clp
            }
        }

        customNavBarRef = WeakReference(customBar)
        customBar.pagerIndexProvider = { currentMainPagerIndex() }
        watchMainTabPager(customBar)
        customScrubber.setReelsActive(customBar.isReels())

        parent.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val curW = right - left
            val curH = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (curW != oldW || curH != oldH) {
                expandContentViews(parent, originalBar)
                val bar = customNavBarRef?.get()
                if (bar != null) {
                    val clp = bar.layoutParams as? FrameLayout.LayoutParams
                    if (clp != null && (clp.height != barHeight || clp.bottomMargin != bottomMargin || clp.leftMargin != horizontalMargin)) {
                        clp.height = barHeight
                        clp.bottomMargin = bottomMargin
                        clp.leftMargin = horizontalMargin
                        clp.rightMargin = horizontalMargin
                        clp.gravity = Gravity.BOTTOM
                        bar.layoutParams = clp
                    }
                }
                val scrubber = customScrubberRef?.get()
                if (scrubber != null) {
                    val slp = scrubber.layoutParams as? FrameLayout.LayoutParams
                    if (slp != null && (slp.height != scrubberHeight || slp.bottomMargin != scrubberBottomMargin || slp.leftMargin != scrubberHorizontalMargin)) {
                        slp.height = scrubberHeight
                        slp.bottomMargin = scrubberBottomMargin
                        slp.leftMargin = scrubberHorizontalMargin
                        slp.rightMargin = scrubberHorizontalMargin
                        slp.gravity = Gravity.BOTTOM
                        scrubber.layoutParams = slp
                    }
                }
            }
        }
    }

    private fun hideOriginalBarVisuals(view: View) {
        view.background = null
        view.alpha = 0f
        if (view is ViewGroup) {
            view.setWillNotDraw(true)
            for (i in 0 until view.childCount) {
                hideOriginalBarVisuals(view.getChildAt(i))
            }
        }
    }

    private fun expandContentViews(parent: ViewGroup, originalBar: View? = null) {
        makeFullHeight(parent)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child !== originalBar && child !is CustomNavBarView && child.tag != "instamod_custom_floating_bar" && child !is CustomReelsScrubberView && child.tag != "instamod_custom_reels_scrubber") {
                makeFullHeight(child)
            }
        }
    }

    private fun makeFullHeight(v: View?) {
        if (v == null) return
        if (v is CustomNavBarView || v.tag == "instamod_custom_floating_bar" || v is CustomReelsScrubberView || v.tag == "instamod_custom_reels_scrubber") return
        try {
            v.fitsSystemWindows = false
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.bottomMargin != 0 || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.bottomMargin = 0
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                v.layoutParams = lp
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // CUSTOM REELS SCRUBBER VIEW (Tactile Apple/Tesla-style Glass Player Scrubber)
    // =========================================================================
    class CustomReelsScrubberView(context: Context) : View(context) {

        companion object {
            /**
             * Translation applied to every reel footer (clips_media_info_component +
             * clips_ufi_component) so it always sits just above this slider.
             * Shared with the View.layout hook so newly mounted reels are aligned
             * before the first sync frame.
             */
            @Volatile
            var footerLiftPx: Float = Float.NaN

            /** Height of the native scrubber, kept even when the provider briefly returns null. */
            @Volatile
            private var nativeScrubberHeightPx: Int = 0

            private val footerViews =
                java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

            fun registerFooter(v: View) {
                synchronized(footerViews) { footerViews.add(v) }
            }

            fun snapshotFooters(): List<View> =
                synchronized(footerViews) { ArrayList(footerViews) }

            fun noteScrubberHeight(h: Int) {
                if (h > 0) nativeScrubberHeightPx = h
            }

            fun scrubberHeight(): Int = nativeScrubberHeightPx
        }

        private var progressFraction = 0f
        private var currentMs = 0
        private var totalMs = 0
        private var isDragging = false
        private var activeRatio = 0f // 0f = idle, 1f = fully expanded/active
        private var activeAnimator: ValueAnimator? = null

        private var isReelsTab = false
        private var isOverlayHidden = false
        private var isReelsActive = false

        var nativeScrubberProvider: (() -> SeekBar?)? = null
        var onSeekListener: ((fraction: Float, action: Int) -> Unit)? = null
        var onTapReel: (() -> Unit)? = null

        private var downX = 0f
        private var downY = 0f
        private var hasPassedSlop = false
        private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

        private var isSyncLoopRunning = false
        private var lastSampledFooterBottom = Float.NaN
        private var lastFollowOffset = 0f
        private val anchorLoc = IntArray(2)
        private var wasFollowing = false

        private val syncRunnable = object : Runnable {
            override fun run() {
                if (!isReelsTab || isOverlayHidden) {
                    isSyncLoopRunning = false
                    translationY = 0f
                    lastFollowOffset = 0f
                    wasFollowing = false
                    return
                }
                if (!isDragging) {
                    val sb = nativeScrubberProvider?.invoke()
                    if (sb != null && sb.max > 0) {
                        updatePlaybackProgress(sb.progress, sb.max)
                    }
                    // In Reels mode, the slider and footer use the earlier shared
                    // geometry-based tracking to stay attached through pager hand-offs.
                    syncReelTracking(sb)
                }
                postOnAnimation(this)
            }
        }

        /**
         * Offset that keeps the slider glued under the footer of the reel it currently
         * belongs to. In Reels mode, the slider and footer move together through each
         * swipe hand-over, using the earlier shared geometry-based placement.
         */
        private fun followOffset(footers: List<View>): Float {
            val root = rootView ?: return 0f
            val pageH = if (root.height > 0) root.height.toFloat()
            else resources.displayMetrics.heightPixels.toFloat()

            // footerLiftPx is the slider-to-footer offset measured from the original
            // native scrubber geometry. Keep it as the resting reference so the same
            // placement works across all reels and pager hand-offs.
            val lift = footerLiftPx
            val nativeH = scrubberHeight()
            if (!lift.isFinite() || nativeH <= 0 || pageH <= 0f || height <= 0) {
                return if (isReelsActive) lastFollowOffset else 0f
            }
            val restBottom = (pageH - nativeH) + lift

            getLocationOnScreen(anchorLoc)
            val restTop = anchorLoc[1] - translationY
            val current = translationY

            var best = Float.NaN
            var bestVisible = false
            for (v in footers) {
                if (!v.isAttachedToWindow || !v.isShown || v.height <= 0) continue
                v.getLocationOnScreen(anchorLoc)
                val d = (anchorLoc[1] + v.height) - restBottom
                if (kotlin.math.abs(d) > pageH) continue // reel more than one screen away
                val sliderTop = restTop + d
                val visible = sliderTop + height > 0f && sliderTop < pageH
                if (!best.isFinite()) {
                    best = d
                    bestVisible = visible
                    continue
                }
                val better = if (visible != bestVisible) visible
                else kotlin.math.abs(d - current) < kotlin.math.abs(best - current)
                if (better) {
                    best = d
                    bestVisible = visible
                }
            }
            // Keep the last reel attachment during the brief pager hand-off when the old
            // footer detaches before the incoming footer has been laid out. The tab/overlay
            // exit above resets this, so it cannot leak into another screen.
            if (!best.isFinite()) return if (isReelsActive) lastFollowOffset else 0f
            if (kotlin.math.abs(best) < 6f) {
                lastFollowOffset = 0f
                return 0f
            }
            lastFollowOffset = best
            return best
        }

        /**
         * Applies [followOffset] and keeps the reel footer lifted above the slider line.
         * Pure function of the live geometry: no easing, no latch, no self animation, so it
         * cannot stall, drift or look re-created while a reel is on screen.
         */
        private fun syncReelTracking(sb: SeekBar?) {
            if (!isAttachedToWindow) return
            val footers = snapshotFooters()
            val target = followOffset(footers)
            if (kotlin.math.abs(translationY - target) > 0.5f) {
                translationY = target
            }
            val following = kotlin.math.abs(target) > 8f
            if (following && !wasFollowing) {
                wasFollowing = true
                XposedBridge.log("[InstaMod] scrubber follows reel")
            } else if (!following && wasFollowing) {
                wasFollowing = false
                XposedBridge.log("[InstaMod] scrubber back home")
            }
            syncFooterLift(sb, footers)
        }

        /**
         * Recomputes and applies the shared lift that keeps each reel footer attached
         * to the custom slider, matching the earlier working Reels placement behavior.
         */
        private fun syncFooterLift(sb: SeekBar?, footers: List<View>) {
            recomputeFooterLift(sb, footers)
            val lift = footerLiftPx
            if (!lift.isFinite()) return
            for (v in footers) {
                if (!v.isAttachedToWindow) continue
                if (kotlin.math.abs(v.translationY - lift) > 0.5f) {
                    v.translationY = lift
                }
            }
        }

        private fun recomputeFooterLift(sb: SeekBar?, footers: List<View>) {
            val root = rootView ?: return
            if (!isAttachedToWindow || height <= 0 || width <= 0) return
            val screenH = if (root.height > 0) root.height.toFloat() else root.resources.displayMetrics.heightPixels.toFloat()

            noteScrubberHeight(sb?.height ?: 0)
            // Instagram pins the reel footer to the native scrubber's top, which itself
            // sits flush with the bottom of the page. Use the remembered scrubber height
            // so the value never drifts when the provider momentarily returns null.
            var nativeH = scrubberHeight()
            if (nativeH <= 0) {
                // Last resort: measure a footer that is parked at the bottom of the screen.
                // Only trust it when two consecutive samples agree (i.e. the reel is still).
                var best = Float.NaN
                for (v in footers) {
                    if (!v.isAttachedToWindow || v.height <= 0 || !v.isShown) continue
                    v.getLocationOnScreen(anchorLoc)
                    val rawBottom = anchorLoc[1] + v.height - v.translationY
                    if (rawBottom < screenH * 0.95f || rawBottom > screenH + 1f) continue
                    if (!best.isFinite() || rawBottom > best) best = rawBottom
                }
                if (!best.isFinite()) return
                if (kotlin.math.abs(best - lastSampledFooterBottom) > 0.5f) {
                    lastSampledFooterBottom = best
                    return
                }
                nativeH = (screenH - best).toInt().coerceAtLeast(0)
                if (nativeH <= 0) return
                noteScrubberHeight(nativeH)
            }

            val naturalFooterBottom = screenH - nativeH

            getLocationOnScreen(anchorLoc)
            val restingTop = anchorLoc[1] - translationY
            val trackTop = restingTop + height -
                ViewUtils.dpToPx(context, 10f) - idleTrackHeightPx / 2f
            val gap = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
            )
            val lift = trackTop - gap - naturalFooterBottom
            if (footerLiftPx.isNaN() || kotlin.math.abs(footerLiftPx - lift) > 0.5f) {
                footerLiftPx = lift
                XposedBridge.log("[InstaMod] reel footer lift=${lift}px (trackTop=$trackTop, restBottom=$naturalFooterBottom)")
            }
        }

        private fun startSyncLoop() {
            if (isSyncLoopRunning) return
            isSyncLoopRunning = true
            XposedBridge.log("[InstaMod] startSyncLoop")
            postOnAnimation(syncRunnable)
        }

        private fun stopSyncLoop() {
            isSyncLoopRunning = false
            XposedBridge.log("[InstaMod] stopSyncLoop")
            removeCallbacks(syncRunnable)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (isReelsTab && !isOverlayHidden) {
                startSyncLoop()
            }
        }

        override fun onDetachedFromWindow() {
            stopSyncLoop()
            super.onDetachedFromWindow()
        }

        private val trackRect = RectF()
        private val progressRect = RectF()
        private val bubbleRect = RectF()

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#4DFFFFFF") // Frosted translucent track
        }

        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#45000000")
        }

        private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#E6141619") // Dark acrylic obsidian glass
        }

        private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ViewUtils.dpToPx(context, 1f).toFloat()
            color = Color.parseColor("#35FFFFFF")
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = ViewUtils.dpToPx(context, 11f).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val idleTrackHeightPx = ViewUtils.dpToPx(context, 3.5f).toFloat()
        private val activeTrackHeightPx = ViewUtils.dpToPx(context, 7f).toFloat()
        private val thumbRadiusPx = ViewUtils.dpToPx(context, 7.5f).toFloat() // 15dp thumb
        private val bubbleWidthPx = ViewUtils.dpToPx(context, 82f).toFloat()
        private val bubbleHeightPx = ViewUtils.dpToPx(context, 24f).toFloat()
        private val bubbleCornerRadiusPx = ViewUtils.dpToPx(context, 12f).toFloat()

        init {
            visibility = View.GONE
            alpha = 0f
            setWillNotDraw(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                translationZ = 40f
            }
        }

        fun updatePlaybackProgress(posMs: Int, durationMs: Int) {
            if (isDragging) return
            if (durationMs <= 0) return
            totalMs = durationMs
            currentMs = posMs.coerceIn(0, durationMs)
            progressFraction = (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

        fun setReelsActive(active: Boolean) {
            if (isReelsTab == active) return
            isReelsTab = active
            isReelsActive = active
            XposedBridge.log("[InstaMod] setReelsActive=$active")
            updateVisibility()
        }

        fun setOverlayHidden(hidden: Boolean) {
            if (isOverlayHidden == hidden) return
            isOverlayHidden = hidden
            updateVisibility()
        }

        fun setTotalDuration(durationMs: Int) {
            if (durationMs > 0) {
                totalMs = durationMs
                if (!isDragging && totalMs > 0) {
                    progressFraction = (currentMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                    postInvalidateOnAnimation()
                }
            }
        }

        private fun updateVisibility() {
            val shouldShow = isReelsTab && !isOverlayHidden
            XposedBridge.log("[InstaMod] updateVisibility isReelsTab=$isReelsTab isOverlayHidden=$isOverlayHidden shouldShow=$shouldShow")
            animate().cancel()
            if (shouldShow) {
                if (visibility != View.VISIBLE) {
                    alpha = 0f
                    visibility = View.VISIBLE
                }
                animate().alpha(1f).setDuration(160).start()
                startSyncLoop()
            } else {
                stopSyncLoop()
                animate().alpha(0f).setDuration(140).withEndAction {
                    visibility = View.GONE
                }.start()
            }
        }

        private fun animateActiveState(active: Boolean) {
            activeAnimator?.cancel()
            val target = if (active) 1f else 0f
            activeAnimator = ValueAnimator.ofFloat(activeRatio, target).apply {
                duration = if (active) 120L else 200L
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    activeRatio = va.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat()
            if (w <= 0f) return super.onTouchEvent(event)

            val trackCenterY = height - ViewUtils.dpToPx(context, 10f).toFloat()
            val trackHitRadius = ViewUtils.dpToPx(context, 16f).toFloat()
            val distToTrack = Math.abs(event.y - trackCenterY)

            if (!isDragging && distToTrack > trackHitRadius) {
                // Taps outside the thin track pass directly to the Reel to toggle play/pause!
                return false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    hasPassedSlop = false
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (!hasPassedSlop) {
                        if (dx > touchSlop && dx > dy) {
                            hasPassedSlop = true
                            isDragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            animateActiveState(true)
                            try {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            } catch (_: Throwable) {}
                            val fraction = (event.x / w).coerceIn(0f, 1f)
                            progressFraction = fraction
                            if (totalMs > 0) currentMs = (fraction * totalMs).toInt()
                            onSeekListener?.invoke(fraction, MotionEvent.ACTION_DOWN)
                            invalidate()
                        }
                    } else {
                        val fraction = (event.x / w).coerceIn(0f, 1f)
                        progressFraction = fraction
                        if (totalMs > 0) currentMs = (fraction * totalMs).toInt()
                        onSeekListener?.invoke(fraction, MotionEvent.ACTION_MOVE)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        val fraction = (event.x / w).coerceIn(0f, 1f)
                        progressFraction = fraction
                        if (totalMs > 0) currentMs = (fraction * totalMs).toInt()
                        onSeekListener?.invoke(fraction, MotionEvent.ACTION_UP)
                        isDragging = false
                        animateActiveState(false)
                        invalidate()
                    } else {
                        // Tap anywhere on the track: jump straight to that point of the
                        // reel, no need to grab the thumb. The drag path above stays
                        // exactly as before for fine scrubbing.
                        val fraction = (event.x / w).coerceIn(0f, 1f)
                        progressFraction = fraction
                        if (totalMs > 0) currentMs = (fraction * totalMs).toInt()
                        onSeekListener?.invoke(fraction, MotionEvent.ACTION_DOWN)
                        onSeekListener?.invoke(fraction, MotionEvent.ACTION_UP)
                        animateActiveState(true)
                        postDelayed({
                            if (!isDragging) animateActiveState(false)
                        }, 220L)
                        try {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } catch (_: Throwable) {
                        }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        isDragging = false
                        animateActiveState(false)
                        onSeekListener?.invoke(progressFraction, MotionEvent.ACTION_CANCEL)
                        invalidate()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val trackW = w
            val currentTrackHeight = idleTrackHeightPx + (activeTrackHeightPx - idleTrackHeightPx) * activeRatio
            val trackRadius = currentTrackHeight / 2f
            val trackCenterY = h - ViewUtils.dpToPx(context, 10f).toFloat()
            val trackTop = trackCenterY - currentTrackHeight / 2f
            val trackBottom = trackCenterY + currentTrackHeight / 2f

            // 1. Frosted track background
            trackRect.set(0f, trackTop, trackW, trackBottom)
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

            // 2. Solid progress fill
            val progressW = (trackW * progressFraction).coerceIn(0f, trackW)
            if (progressW > 0f) {
                progressRect.set(0f, trackTop, progressW, trackBottom)
                canvas.drawRoundRect(progressRect, trackRadius, trackRadius, progressPaint)
            }

            // 3. Thumb & floating tooltip bubble
            if (activeRatio > 0.01f) {
                val thumbRadius = thumbRadiusPx * activeRatio
                val thumbX = progressW.coerceIn(thumbRadius, trackW - thumbRadius)

                // Thumb shadow & circle
                canvas.drawCircle(thumbX, trackCenterY + 1.5f, thumbRadius + 1.5f, thumbShadowPaint)
                canvas.drawCircle(thumbX, trackCenterY, thumbRadius, thumbPaint)

                // Floating timestamp tooltip bubble
                val bubbleAlpha = (activeRatio * 255).toInt().coerceIn(0, 255)
                bubbleBgPaint.alpha = (activeRatio * 230).toInt().coerceIn(0, 230)
                bubbleBorderPaint.alpha = (activeRatio * 55).toInt().coerceIn(0, 55)
                textPaint.alpha = bubbleAlpha

                val bubbleCenterY = trackTop - ViewUtils.dpToPx(context, 16f).toFloat()
                val halfBubbleW = bubbleWidthPx / 2f
                val bubbleLeft = (thumbX - halfBubbleW).coerceIn(4f, trackW - bubbleWidthPx - 4f)
                val bubbleTop = bubbleCenterY - bubbleHeightPx / 2f
                val bubbleRight = bubbleLeft + bubbleWidthPx
                val bubbleBottom = bubbleCenterY + bubbleHeightPx / 2f

                bubbleRect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
                canvas.drawRoundRect(bubbleRect, bubbleCornerRadiusPx, bubbleCornerRadiusPx, bubbleBgPaint)
                canvas.drawRoundRect(bubbleRect, bubbleCornerRadiusPx, bubbleCornerRadiusPx, bubbleBorderPaint)

                val timeText = "${formatTime(currentMs)} / ${formatTime(totalMs)}"
                val textBaseline = bubbleCenterY - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(timeText, bubbleRect.centerX(), textBaseline, textPaint)
            }
        }

        private fun formatTime(ms: Int): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    // =========================================================================
    // CUSTOM REBUILT FLOATING BAR VIEW (Frosted Glass & Dynamic Dock)
    // =========================================================================
    class CustomNavBarView(
        context: Context,
        private val originalTabBar: ViewGroup,
        private val onExpandRequested: () -> Unit,
        private val onTabClick: (Int) -> Unit,
        private val onReelsStateChanged: ((Boolean) -> Unit)? = null,
        private val onOverlayHiddenChanged: ((Boolean) -> Unit)? = null
    ) : FrameLayout(context) {

        class BlurCanvas(bitmap: Bitmap) : Canvas(bitmap)

        private val sliderPill: View
        private val tabsContainer: LinearLayout
        private val tabViews = mutableListOf<FrameLayout>()
        private val iconViews = mutableListOf<ImageView>()
        private val badgeViews = mutableListOf<View?>()

        private val pillBackgroundDrawable: GradientDrawable
        /** Uniform gap (dp) between the selector capsule and the bar outline. */
        private val PILL_INSET_DP = 7f

        private var currentBgColor = Color.parseColor("#D4141619")
        private var currentStrokeColor = Color.parseColor("#2CFFFFFF")
        private val strokeWidthPx by lazy { ViewUtils.dpToPx(context, 1f) }

        private var blurBitmap: Bitmap? = null
        private var blurCanvas: BlurCanvas? = null
        private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val capsulePath = Path()
        private val capsuleRect = RectF()
        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private var isRenderingBlur = false
        private var isOverlayHidden = false
        private var reelViewerId = 0

        private var currentActiveIndex = 0
        private var isReelsActive = false
        private var currentReelsRatio = 0f
        private var colorAnimator: ValueAnimator? = null

        /** Main tab pager position, polled as a safety net for side swipes. */
        var pagerIndexProvider: (() -> Int?)? = null
        private var lastPagerCheck = 0L
        private var lastPagerIndex = -1
        private var pillAnimationUntil = 0L
        private var lastLayoutPillW = 0
        private var lastLayoutPillH = 0
        /** Tab the capsule animation is heading to (-1 = none). */
        private var animatingToIndex = -1
        /** Set when the glass backdrop must be re-rendered, so idle frames cost nothing. */
        private var blurDirty = true

        private val nightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val standardBgColor =
            if (nightMode) Color.parseColor("#D4141619") else Color.parseColor("#D4F0F2F5")
        private val reelsBgColor = Color.parseColor("#D8101214")
        private val standardStrokeColor =
            if (nightMode) Color.parseColor("#2CFFFFFF") else Color.parseColor("#18000000")
        private val reelsStrokeColor = Color.parseColor("#24FFFFFF")
        private val standardPillColor =
            if (nightMode) Color.parseColor("#DE3E4146") else Color.parseColor("#25000000")
        private val reelsPillColor = Color.parseColor("#E03E4146")
        private val standardPillStrokeColor =
            if (nightMode) Color.parseColor("#24FFFFFF") else Color.parseColor("#15000000")
        private val reelsPillStrokeColor = Color.parseColor("#35FFFFFF")

        private var appliedBgColor = 0
        private var appliedStrokeColor = 0
        private var appliedPillColor = 0
        private var appliedPillStroke = 0
        private var appliedIconTint = Int.MIN_VALUE

        private var isScrolling = false
        private val stopScrollingRunnable = Runnable {
            isScrolling = false
            blurDirty = true
            postInvalidateOnAnimation()
        }

        fun setScrolling(scrolling: Boolean) {
            removeCallbacks(stopScrollingRunnable)
            if (scrolling) {
                isScrolling = true
            } else {
                // Debounce returning to idle by 300ms so fling animations finish smoothly before re-enabling blur updates
                postDelayed(stopScrollingRunnable, 300L)
            }
        }

        private var lastBlurTime = 0L
        // The glass backdrop only has to look plausible: rendering the whole view tree into a
        // bitmap is by far the most expensive thing this bar does. It is refreshed on demand
        // (new section, scroll ended) instead of on a timer, so a still screen costs nothing.
        private val BLUR_MIN_INTERVAL_MS = 120L

        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (isShown && width > 0 && height > 0) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPagerCheck > 90L) {
                    lastPagerCheck = now
                    val index = pagerIndexProvider?.invoke()
                    if (index != null && index != lastPagerIndex) {
                        lastPagerIndex = index
                        if (index != currentActiveIndex && !isDraggingSlider) {
                            setSelectedTab(index, animate = true)
                        }
                    }
                }
                if (blurDirty && !isScrolling && now >= pillAnimationUntil &&
                    now - lastBlurTime > BLUR_MIN_INTERVAL_MS
                ) {
                    blurDirty = false
                    lastBlurTime = now
                    updateBlur()
                }
            }
            true
        }

        fun setHiddenByOverlay(hidden: Boolean) {
            if (isOverlayHidden == hidden) return
            isOverlayHidden = hidden
            onOverlayHiddenChanged?.invoke(hidden)
            animate().cancel()
            if (hidden) {
                alpha = 0f
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                alpha = 1f
                scaleX = 1.0f
                scaleY = 1.0f
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(stopScrollingRunnable)
            viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            blurBitmap?.recycle()
            blurBitmap = null
            blurCanvas = null
            super.onDetachedFromWindow()
        }

        init {
            setWillNotDraw(false)
            elevation = ViewUtils.dpToPx(context, 8f).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                translationZ = 50f
            }

            reelViewerId = context.resources.getIdentifier("reel_viewer_root", "id", context.packageName)

            val radiusPx = ViewUtils.dpToPx(context, 22f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = if (view.width > 0) view.width else 1
                    val h = if (view.height > 0) view.height else 1
                    outline.setRoundRect(0, 0, w, h, radiusPx)
                }
            }
            clipToOutline = true

            // 1. Sliding capsule indicator (slate/charcoal glass pill)
            val initialPillWidth = ViewUtils.dpToPx(context, 48f)
            val pillHeight = ViewUtils.dpToPx(context, 36f)
            val pillRadiusPx = ViewUtils.dpToPx(context, 18f).toFloat()
            pillBackgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = pillRadiusPx
            }
            sliderPill = View(context).apply {
                background = pillBackgroundDrawable
                // The pill is a static rounded rect that only ever translates: keep it on a
                // permanent hardware layer so every frame of the slide is a pure transform
                // instead of a re-rasterisation (and avoid the per-animation .withLayer()
                // allocation churn that used to hitch the first frames).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }
            }
            val pillLp = LayoutParams(initialPillWidth, pillHeight, Gravity.TOP or Gravity.START)
            addView(sliderPill, pillLp)

            // 2. Horizontal container for 5 tabs
            tabsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
            }
            addView(tabsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            buildTabs()
            updateColors(reelsRatio = 0f)

            // Periodic sync to ensure all icons and badges are caught
            postDelayed({ syncFromOriginal() }, 100)
            postDelayed({ syncFromOriginal() }, 300)
            postDelayed({ syncFromOriginal() }, 800)
            postDelayed({ syncFromOriginal() }, 1500)

            originalTabBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                removeCallbacks(syncRunnable)
                postDelayed(syncRunnable, 150L)
            }
        }

        fun isReels(): Boolean = isReelsActive

        private fun updateBlur() {
            if (isScrolling || isOverlayHidden || visibility != View.VISIBLE || alpha <= 0.5f) return
            if (android.os.SystemClock.uptimeMillis() < pillAnimationUntil) return

            // Fast check: if story root is present on screen, hide immediately
            if (reelViewerId != 0) {
                val root = rootView as? ViewGroup
                val reelViewer = root?.findViewById<View>(reelViewerId)
                if (reelViewer != null && reelViewer.visibility == View.VISIBLE && reelViewer.width > 0) {
                    setHiddenByOverlay(true)
                    return
                }
            }

            val parentView = parent as? ViewGroup ?: return
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return

            // Reels: the video is a SurfaceView, so this capture only sees the
            // transparent hole Instagram leaves for it (i.e. black). The module's
            // compositor blur layer covers that hole instead, so capturing here
            // would just burn CPU to paint black.
            if (isReelsActive) return

            val scaleFactor = 10f
            val targetW = (w / scaleFactor).toInt().coerceAtLeast(1)
            val targetH = (h / scaleFactor).toInt().coerceAtLeast(1)

            var bm = blurBitmap
            if (bm == null || bm.isRecycled || bm.width != targetW || bm.height != targetH) {
                bm?.recycle()
                try {
                    bm = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    blurBitmap = bm
                    blurCanvas = BlurCanvas(bm)
                } catch (_: Throwable) {
                    return
                }
            }
            val canvas = blurCanvas ?: return

            isRenderingBlur = true
            canvas.save()
            bm.eraseColor(Color.TRANSPARENT)
            canvas.scale(1f / scaleFactor, 1f / scaleFactor)
            canvas.translate(-left.toFloat(), -top.toFloat())

            try {
                parentView.draw(canvas)
            } catch (_: Throwable) {
            } finally {
                canvas.restore()
                isRenderingBlur = false
            }

            try {
                FastBlur.stackBlur(bm, 2)
            } catch (_: Throwable) {}
        }

        override fun draw(canvas: Canvas) {
            if (isOverlayHidden || isRenderingBlur || canvas is BlurCanvas) return

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) {
                super.draw(canvas)
                return
            }

            val radius = h / 2f
            capsuleRect.set(0f, 0f, w, h)
            capsulePath.reset()
            capsulePath.addRoundRect(capsuleRect, radius, radius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(capsulePath)

            if (isReelsActive) {
                // Reels: the capsule is see-through instead of opaque so the
                // compositor blur layer (which sits *below* the window, over the
                // video SurfaceView) shows through: the video reads as glass under
                // the bar. Clearing the pixels is required because everything drawn
                // earlier in this window buffer is already opaque black there.
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                overlayPaint.color = Color.argb(
                    0x59,
                    Color.red(currentBgColor),
                    Color.green(currentBgColor),
                    Color.blue(currentBgColor),
                )
                canvas.drawRect(capsuleRect, overlayPaint)
            } else {
                // 1. Draw live blurred backdrop
                val bm = blurBitmap
                if (bm != null && !bm.isRecycled) {
                    canvas.drawBitmap(bm, null, capsuleRect, blurPaint)
                }

                // 2. Draw dark translucent glass tint overlay
                overlayPaint.color = currentBgColor
                canvas.drawRect(capsuleRect, overlayPaint)
            }

            // 3. Draw children (slider pill, icons, badges)
            super.draw(canvas)

            // 4. Draw 1dp specular stroke on top of edges
            borderPaint.color = currentStrokeColor
            borderPaint.strokeWidth = strokeWidthPx.toFloat()
            canvas.drawRoundRect(capsuleRect, radius, radius, borderPaint)

            canvas.restore()
        }

        private fun buildTabs() {
            tabsContainer.removeAllViews()
            tabViews.clear()
            iconViews.clear()
            badgeViews.clear()

            val tabCount = 5
            for (i in 0 until tabCount) {
                val tabFrame = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    isClickable = false
                    isFocusable = false
                }

                // Sleek, refined icon size
                val iconView = ImageView(context).apply {
                    val iconSize = ViewUtils.dpToPx(context, 22f)
                    layoutParams = LayoutParams(iconSize, iconSize, Gravity.CENTER)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                // Profile avatar circular mask
                if (i == 4) {
                    iconView.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            val size = Math.min(view.width, view.height)
                            outline.setOval(0, 0, size, size)
                        }
                    }
                    iconView.clipToOutline = true
                }

                tabFrame.addView(iconView)
                iconViews.add(iconView)

                // Minimalist red notification dot (7dp circle, iOS style)
                var badgeDot: View? = null
                if (i == 2 || i == 4) {
                    badgeDot = View(context).apply {
                        val dotSize = ViewUtils.dpToPx(context, 7f)
                        val bLp = LayoutParams(dotSize, dotSize, Gravity.CENTER).apply {
                            setMargins(ViewUtils.dpToPx(context, 9f), ViewUtils.dpToPx(context, 8f), 0, 0)
                        }
                        layoutParams = bLp
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#FF3B30"))
                        }
                        visibility = View.GONE
                    }
                    tabFrame.addView(badgeDot)
                }
                badgeViews.add(badgeDot)

                tabsContainer.addView(tabFrame)
                tabViews.add(tabFrame)
            }

            syncFromOriginal()
        }

        private var downX = 0f
        private var downY = 0f
        private var isDraggingSlider = false
        private var lastUserClickTime = 0L
        private val syncRunnable = Runnable { syncFromOriginal() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val totalW = width
            if (totalW <= 0) return super.onTouchEvent(event)
            val tabWidth = totalW / 5f

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.x
                    downY = event.y
                    isDraggingSlider = false
                    val targetTab = (event.x / tabWidth).toInt().coerceIn(0, 4)
                    lastUserClickTime = android.os.SystemClock.uptimeMillis()
                    setSelectedTab(targetTab, animate = true)
                    onExpandRequested()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx > 25 && dx > dy) {
                        isDraggingSlider = true
                        updateSliderPositionForTouch(event.x)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val targetTab = (event.x / tabWidth).toInt().coerceIn(0, 4)
                    lastUserClickTime = android.os.SystemClock.uptimeMillis()
                    onTabClick(targetTab)
                    setSelectedTab(targetTab, animate = true)
                    isDraggingSlider = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    setSelectedTab(currentActiveIndex, animate = true)
                    isDraggingSlider = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun getTargetPillX(tabIndex: Int): Float {
            if (tabIndex !in 0 until tabViews.size) return 0f
            val tab = tabViews[tabIndex]
            val icon = iconViews.getOrNull(tabIndex)
            return if (icon != null && icon.width > 0) {
                val iconCenterX = tab.left + icon.left + icon.width / 2f
                iconCenterX - sliderPill.width / 2f
            } else {
                val tabW = if (width > 0) width / 5f else 0f
                val tabCenterX = (tabIndex + 0.5f) * tabW
                tabCenterX - sliderPill.width / 2f
            }
        }

        private fun getTargetPillY(tabIndex: Int): Float {
            if (tabIndex !in 0 until tabViews.size) return 0f
            val tab = tabViews[tabIndex]
            val icon = iconViews.getOrNull(tabIndex)
            return if (icon != null && icon.height > 0) {
                val iconCenterY = tab.top + icon.top + icon.height / 2f
                iconCenterY - sliderPill.height / 2f
            } else {
                val h = if (height > 0) height.toFloat() else ViewUtils.dpToPx(context, 44f).toFloat()
                (h - sliderPill.height) / 2f
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            updatePillBounds()
            // Only re-anchor the capsule when its own size changed (first layout, bar resize).
            // Snapping it on every layout pass used to fight the running animation, which is
            // what made the moving icon stutter and freeze during tab swipes.
            val pw = sliderPill.width
            val ph = sliderPill.height
            if (pw != lastLayoutPillW || ph != lastLayoutPillH) {
                lastLayoutPillW = pw
                lastLayoutPillH = ph
                sliderPill.animate().cancel()
                animatingToIndex = -1
                sliderPill.translationX = getTargetPillX(currentActiveIndex)
                sliderPill.translationY = getTargetPillY(currentActiveIndex)
            }
        }

        private fun updateSliderPositionForTouch(touchX: Float) {
            val totalW = width
            if (totalW <= 0) return
            val minX = getTargetPillX(0)
            val maxX = getTargetPillX(4)

            val targetX = (touchX - sliderPill.width / 2f).coerceIn(minX, maxX)
            sliderPill.animate().cancel()
            sliderPill.translationX = targetX
            sliderPill.translationY = getTargetPillY(currentActiveIndex)

            val tabWidth = totalW / 5f
            val virtualPos = if (tabWidth > 0f) (targetX - minX) / tabWidth else 0f
            val distToReels = Math.abs(virtualPos - 1f)
            val reelsRatio = (1f - distToReels).coerceIn(0f, 1f)
            currentReelsRatio = reelsRatio
            updateColors(reelsRatio)
        }

        private var hasLoggedTabs = false

        fun syncFromOriginal() {
            val now = android.os.SystemClock.uptimeMillis()
            val isRecentlyClicked = (now - lastUserClickTime < 600)

            if (!hasLoggedTabs && originalTabBar.childCount > 0) {
                hasLoggedTabs = true
                for (idx in 0 until originalTabBar.childCount) {
                    val t = originalTabBar.getChildAt(idx)
                    val name = try { t.resources.getResourceEntryName(t.id) } catch (_: Throwable) { "" }
                    XposedBridge.log("[InstaMod] Tab $idx: idName=$name, desc=${t.contentDescription}")
                }
            }

            // Copy drawables from original Instagram tabs
            for (i in 0 until 5) {
                if (i < originalTabBar.childCount) {
                    val origTab = originalTabBar.getChildAt(i)
                    val icon = extractDrawable(origTab, i)
                    if (icon != null && i < iconViews.size) {
                        // Re-applying an identical drawable re-invalidates the icon every
                        // layout pass for nothing; only swap when it is really different.
                        val current = iconViews[i].drawable
                        val same = current != null && current.constantState != null &&
                            current.constantState === icon.constantState
                        if (!same) iconViews[i].setImageDrawable(icon)
                    }

                    // Check selected
                    if (origTab.isSelected && i != currentActiveIndex &&
                        !isDraggingSlider && !isRecentlyClicked
                    ) {
                        setSelectedTab(i, animate = true)
                    }

                    // Check badge
                    val hasUnread = hasBadge(origTab)
                    val badge = badgeViews.getOrNull(i)
                    if (badge != null) {
                        val wanted = if (hasUnread) View.VISIBLE else View.GONE
                        if (badge.visibility != wanted) badge.visibility = wanted
                    }
                }
            }
        }

        private fun hasBadge(view: View): Boolean {
            if (view is TextView && view.text.isNotEmpty() && view.visibility == View.VISIBLE) {
                val t = view.text.toString().trim()
                if (t.isNotEmpty() && t != "0") return true
            }
            if (view.id != View.NO_ID) {
                try {
                    val name = view.resources.getResourceEntryName(view.id).lowercase()
                    if ((name.contains("badge") || name.contains("dot") || name.contains("notification")) && view.visibility == View.VISIBLE) {
                        return true
                    }
                } catch (_: Throwable) {}
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (hasBadge(view.getChildAt(i))) return true
                }
            }
            return false
        }

        private fun extractDrawable(view: View, tabIndex: Int): Drawable? {
            // For profile avatar (tab 4), look for CircularImageView or tab_avatar
            if (tabIndex == 4) {
                val avatar = findAvatar(view)
                if (avatar != null && avatar.drawable != null) {
                    return avatar.drawable.constantState?.newDrawable()?.mutate() ?: avatar.drawable
                }
            }

            if (view is ImageView && view.drawable != null) {
                val d = view.drawable.constantState?.newDrawable()?.mutate() ?: view.drawable
                if (tabIndex != 4) {
                    d.clearColorFilter()
                }
                return d
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child is ImageView && child.drawable != null) {
                        val d = child.drawable.constantState?.newDrawable()?.mutate() ?: child.drawable
                        if (tabIndex != 4) {
                            d.clearColorFilter()
                        }
                        return d
                    }
                }
                for (i in 0 until view.childCount) {
                    val d = extractDrawable(view.getChildAt(i), tabIndex)
                    if (d != null) return d
                }
            }
            return null
        }

        private fun findAvatar(view: View): ImageView? {
            if (view is ImageView && (view.javaClass.name.contains("Circular") || (view.id != View.NO_ID && view.resources.getResourceEntryName(view.id).contains("avatar")))) {
                return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = findAvatar(view.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            blurDirty = true
            updatePillBounds()
        }

        private fun updatePillBounds() {
            val totalW = width
            val totalH = height
            if (totalW > 0 && totalH > 0) {
                // One single margin for every side: the capsule is inset from the bar outline by
                // exactly the same amount above, below and on the sides. With a pill radius of
                // half its height it also stays concentric with the round ends of the bar, so
                // the gap is visually constant all the way around.
                val inset = ViewUtils.dpToPx(context, PILL_INSET_DP)
                val tabWidth = totalW / 5f
                val pillWidth = (tabWidth - inset * 2).toInt()
                val pillHeight = (totalH - inset * 2).toInt()

                val lp = sliderPill.layoutParams as? LayoutParams
                val resized = lp != null && (lp.width != pillWidth || lp.height != pillHeight)
                if (lp != null && resized) {
                    lp.width = pillWidth
                    lp.height = pillHeight
                    sliderPill.layoutParams = lp
                }
                val radius = pillHeight / 2f
                if (pillBackgroundDrawable.cornerRadius != radius) {
                    pillBackgroundDrawable.cornerRadius = radius
                }
                if (resized) {
                    // Width changed: the X anchor depends on it.
                    lastLayoutPillW = 0
                    lastLayoutPillH = 0
                }
            }
        }

        private val reelsTabCache = BooleanArray(5) { it == 1 }
        private var reelsTabCacheReady = false

        /** The tab bar hierarchy is walked once, not on every selection change. */
        private fun refreshReelsTabCache() {
            if (reelsTabCacheReady || originalTabBar.childCount == 0) return
            for (i in 0 until 5) {
                reelsTabCache[i] = if (i < originalTabBar.childCount) {
                    hasClipsOrReels(originalTabBar.getChildAt(i)) || i == 1
                } else {
                    i == 1
                }
            }
            reelsTabCacheReady = true
        }

        private fun isTabReels(index: Int): Boolean {
            if (index !in 0..4) return false
            refreshReelsTabCache()
            return reelsTabCache[index]
        }

        private fun hasClipsOrReels(v: View): Boolean {
            val desc = v.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("reels") || desc.contains("clips") || desc.contains("reel")) return true
            if (v.id != View.NO_ID) {
                try {
                    val name = v.resources.getResourceEntryName(v.id).lowercase()
                    if (name.contains("clips") || name.contains("reels") || name.contains("reel")) return true
                } catch (_: Throwable) {}
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (hasClipsOrReels(v.getChildAt(i))) return true
                }
            }
            return false
        }

        fun setSelectedTab(index: Int, animate: Boolean) {
            val targetIndex = index.coerceIn(0, 4)
            val targetX = getTargetPillX(targetIndex)
            val targetY = getTargetPillY(targetIndex)
            // A touch down, the touch up and the pager poll all report the same tab, and the
            // animation is usually still running: re-targeting it restarts the easing curve
            // from the current position, which is exactly what made the capsule look jerky.
            if (animate && targetIndex == animatingToIndex) return
            animatingToIndex = targetIndex
            blurDirty = true

            val wasReels = isReelsActive
            currentActiveIndex = targetIndex
            isReelsActive = isTabReels(currentActiveIndex)
            if (wasReels != isReelsActive) {
                onReelsStateChanged?.invoke(isReelsActive)
            }

            if (animate) {
                pillAnimationUntil = android.os.SystemClock.uptimeMillis() + 200L
                sliderPill.animate().cancel()
                sliderPill.animate()
                    .translationX(targetX)
                    .translationY(targetY)
                    .setDuration(140)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                sliderPill.animate().cancel()
                sliderPill.translationX = targetX
                sliderPill.translationY = targetY
            }

            val targetReelsRatio = if (isReelsActive) 1.0f else 0.0f
            if (animate && wasReels != isReelsActive) {
                colorAnimator?.cancel()
                colorAnimator = ValueAnimator.ofFloat(currentReelsRatio, targetReelsRatio).apply {
                    duration = 140
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        val r = va.animatedValue as Float
                        currentReelsRatio = r
                        updateColors(r)
                    }
                    start()
                }
            } else if (!animate) {
                colorAnimator?.cancel()
                currentReelsRatio = targetReelsRatio
                updateColors(targetReelsRatio)
            }

            // Subtle scale for the active icon (skip views that are already there)
            for (i in iconViews.indices) {
                val iv = iconViews[i]
                val scale = if (i == currentActiveIndex) 1.10f else 1.0f
                if (kotlin.math.abs(iv.scaleX - scale) < 0.001f &&
                    kotlin.math.abs(iv.scaleY - scale) < 0.001f
                ) {
                    continue
                }
                if (animate) {
                    iv.animate().scaleX(scale).scaleY(scale)
                        .setDuration(140)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    iv.scaleX = scale
                    iv.scaleY = scale
                }
            }
        }

        private fun updateColors(reelsRatio: Float) {
            currentBgColor = interpolateColor(standardBgColor, reelsBgColor, reelsRatio)
            currentStrokeColor = interpolateColor(standardStrokeColor, reelsStrokeColor, reelsRatio)

            if (currentBgColor != appliedBgColor || currentStrokeColor != appliedStrokeColor) {
                appliedBgColor = currentBgColor
                appliedStrokeColor = currentStrokeColor
                invalidate()
            }

            val pillColor = interpolateColor(standardPillColor, reelsPillColor, reelsRatio)
            val pillStroke = interpolateColor(standardPillStrokeColor, reelsPillStrokeColor, reelsRatio)
            if (pillColor != appliedPillColor || pillStroke != appliedPillStroke) {
                appliedPillColor = pillColor
                appliedPillStroke = pillStroke
                pillBackgroundDrawable.setColor(pillColor)
                pillBackgroundDrawable.setStroke(strokeWidthPx, pillStroke)
            }

            val normalIconColor = if (nightMode) Color.WHITE else Color.BLACK
            val tint = if (reelsRatio > 0.02f) {
                interpolateColor(normalIconColor, Color.WHITE, reelsRatio)
            } else if (nightMode) {
                Color.WHITE
            } else {
                Int.MIN_VALUE // no tint at all in light mode
            }
            if (tint != appliedIconTint) {
                appliedIconTint = tint
                val tintList = if (tint == Int.MIN_VALUE) null else ColorStateList.valueOf(tint)
                for (i in iconViews.indices) {
                    iconViews[i].imageTintList = if (i == 4) null else tintList
                }
            }
        }

        private fun interpolateColor(colorFrom: Int, colorTo: Int, fraction: Float): Int {
            val f = fraction.coerceIn(0f, 1f)
            val a = (Color.alpha(colorFrom) + f * (Color.alpha(colorTo) - Color.alpha(colorFrom))).toInt()
            val r = (Color.red(colorFrom) + f * (Color.red(colorTo) - Color.red(colorFrom))).toInt()
            val g = (Color.green(colorFrom) + f * (Color.green(colorTo) - Color.green(colorFrom))).toInt()
            val b = (Color.blue(colorFrom) + f * (Color.blue(colorTo) - Color.blue(colorFrom))).toInt()
            return Color.argb(a, r, g, b)
        }
    }

    // =========================================================================
    // HIGH-PERFORMANCE FAST BLUR (Mario Klingemann StackBlur, Zero Allocations)
    // =========================================================================
    private object FastBlur {
        private var cachedWh = 0
        private var pix: IntArray? = null
        private var r: IntArray? = null
        private var g: IntArray? = null
        private var b: IntArray? = null
        private var vmin: IntArray? = null
        private var dv: IntArray? = null
        private var dvSum = 0

        fun stackBlur(bitmap: Bitmap, radius: Int) {
            if (radius < 1) return
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return

            val wh = w * h
            if (pix == null || cachedWh < wh) {
                cachedWh = wh
                pix = IntArray(wh)
                r = IntArray(wh)
                g = IntArray(wh)
                b = IntArray(wh)
                vmin = IntArray(Math.max(w, h))
            }
            val pixArr = pix!!
            val rArr = r!!
            val gArr = g!!
            val bArr = b!!
            val vminArr = vmin!!

            val div = radius + radius + 1
            var divsum = (div + 1) shr 1
            divsum *= divsum
            if (dv == null || dvSum != divsum) {
                dvSum = divsum
                dv = IntArray(256 * divsum)
                for (idx in 0 until 256 * divsum) {
                    dv!![idx] = idx / divsum
                }
            }
            val dvArr = dv!!

            bitmap.getPixels(pixArr, 0, w, 0, 0, w, h)

            val wm = w - 1
            val hm = h - 1
            val r1 = radius + 1

            var rsum: Int
            var gsum: Int
            var bsum: Int
            var p: Int
            var yp: Int
            var yi = 0
            var yw = 0

            val stack = Array(div) { IntArray(3) }
            var stackpointer: Int
            var stackstart: Int
            var sir: IntArray
            var rbs: Int
            var routsum: Int
            var goutsum: Int
            var boutsum: Int
            var rinsum: Int
            var ginsum: Int
            var binsum: Int

            for (yIdx in 0 until h) {
                rinsum = 0; ginsum = 0; binsum = 0
                routsum = 0; goutsum = 0; boutsum = 0
                rsum = 0; gsum = 0; bsum = 0
                for (idx in -radius..radius) {
                    p = pixArr[yi + Math.min(wm, Math.max(idx, 0))]
                    sir = stack[idx + radius]
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)
                    rbs = r1 - Math.abs(idx)
                    rsum += sir[0] * rbs
                    gsum += sir[1] * rbs
                    bsum += sir[2] * rbs
                    if (idx > 0) {
                        rinsum += sir[0]
                        ginsum += sir[1]
                        binsum += sir[2]
                    } else {
                        routsum += sir[0]
                        goutsum += sir[1]
                        boutsum += sir[2]
                    }
                }
                stackpointer = radius

                for (xIdx in 0 until w) {
                    rArr[yi] = dvArr[rsum]
                    gArr[yi] = dvArr[gsum]
                    bArr[yi] = dvArr[bsum]

                    rsum -= routsum
                    gsum -= goutsum
                    bsum -= boutsum

                    stackstart = stackpointer - radius + div
                    sir = stack[stackstart % div]

                    routsum -= sir[0]
                    goutsum -= sir[1]
                    boutsum -= sir[2]

                    if (yIdx == 0) {
                        vminArr[xIdx] = Math.min(xIdx + radius + 1, wm)
                    }
                    p = pixArr[yw + vminArr[xIdx]]

                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)

                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]

                    rsum += rinsum
                    gsum += ginsum
                    bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer % div]

                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]

                    rinsum -= sir[0]
                    ginsum -= sir[1]
                    binsum -= sir[2]

                    yi++
                }
                yw += w
            }

            for (xIdx in 0 until w) {
                rinsum = 0; ginsum = 0; binsum = 0
                routsum = 0; goutsum = 0; boutsum = 0
                rsum = 0; gsum = 0; bsum = 0
                yp = -radius * w
                for (idx in -radius..radius) {
                    yi = Math.max(0, yp) + xIdx
                    sir = stack[idx + radius]
                    sir[0] = rArr[yi]
                    sir[1] = gArr[yi]
                    sir[2] = bArr[yi]
                    rbs = r1 - Math.abs(idx)
                    rsum += rArr[yi] * rbs
                    gsum += gArr[yi] * rbs
                    bsum += bArr[yi] * rbs
                    if (idx > 0) {
                        rinsum += sir[0]
                        ginsum += sir[1]
                        binsum += sir[2]
                    } else {
                        routsum += sir[0]
                        goutsum += sir[1]
                        boutsum += sir[2]
                    }
                    if (idx < hm) {
                        yp += w
                    }
                }
                yi = xIdx
                stackpointer = radius
                for (yIdx in 0 until h) {
                    pixArr[yi] = (pixArr[yi] and -0x1000000) or (dvArr[rsum] shl 16) or (dvArr[gsum] shl 8) or dvArr[bsum]
                    rsum -= routsum
                    gsum -= goutsum
                    bsum -= boutsum

                    stackstart = stackpointer - radius + div
                    sir = stack[stackstart % div]

                    routsum -= sir[0]
                    goutsum -= sir[1]
                    boutsum -= sir[2]

                    if (xIdx == 0) {
                        vminArr[yIdx] = Math.min(yIdx + r1, hm) * w
                    }
                    p = xIdx + vminArr[yIdx]

                    sir[0] = rArr[p]
                    sir[1] = gArr[p]
                    sir[2] = bArr[p]

                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]

                    rsum += rinsum
                    gsum += ginsum
                    bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer]

                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]

                    rinsum -= sir[0]
                    ginsum -= sir[1]
                    binsum -= sir[2]

                    yi += w
                }
            }

            bitmap.setPixels(pixArr, 0, w, 0, 0, w, h)
        }
    }
}
