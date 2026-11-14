package com.kinko.dockflow.hooks

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Styles YouTube's native pivot bar into a floating Liquid Glass capsule.
 * Native tabs, icons and click handlers are preserved; only the surface is redrawn.
 */
public class YoutubeFloatingNavHook {

    private companion object {
        const val TAG = "instamod_youtube_dock"
        const val DEFAULT_SLOTS = 5
        const val CREATE_SLOT = 2
        const val SUBS_SLOT = 3
        const val TAG_SUBS = "instamod_subs_row"
        const val TAG_SUBS_SLOT = "instamod_subs_slot"
        const val TAG_SUBS_ICON = "instamod_subs_icon"
        const val CAPTURE_SCALE = 0.5f
        const val CAPTURE_INTERVAL = 150L
        const val BLUR_RADIUS = 9
        const val LENS_DARK_THRESHOLD = 88

        /** Width of the refractive rim: min(12dp, radius * 0.2) at capsule level. */
        const val REFRACT_BAND = 0.20f

        /** How far the rim pulls the backdrop inwards, as a fraction of the height. */
        const val REFRACT_AMOUNT = 0.10f

        /** Chromatic dispersion inside the rim. 0 = neutral glass (no chromatic aberration). */
        const val REFRACT_CHROME = 0.0f

        /**
         * PrismalAGSL-style rounded-rect lens, inlined as AGSL (API 33+).
         * `content` is the blurred backdrop; near the capsule and pill edges the
         * coordinate is displaced along the signed-distance gradient so the rim
         * behaves like real glass (edge compression + optional dispersion).
         */
        const val REFRACTION_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 contentScale;
uniform float radius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float chrome;
uniform float4 bubble;
uniform float bubbleRefraction;

float arcLift(float x) {
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

float sdRoundRect(float2 coord, float2 halfSize, float r) {
    float2 c = abs(coord) - (halfSize - float2(r));
    float outside = length(max(c, float2(0.0))) - r;
    float inside = min(max(c.x, c.y), 0.0);
    return outside + inside;
}

float2 gradRoundRect(float2 coord, float2 halfSize, float r) {
    float2 c = abs(coord) - (halfSize - float2(r));
    if (c.x >= 0.0 || c.y >= 0.0) {
        return sign(coord) * normalize(max(c, float2(0.0)));
    }
    float gx = step(c.y, c.x);
    return sign(coord) * float2(gx, 1.0 - gx);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float2 shift = float2(0.0);
    float sd = sdRoundRect(p, halfSize, radius);
    if (-sd < refractionHeight) {
        float inside = -min(sd, 0.0);
        float d = arcLift(1.0 - inside / refractionHeight) * refractionAmount;
        float gr = min(radius * 1.5, min(halfSize.x, halfSize.y));
        float2 g = normalize(gradRoundRect(p, halfSize, gr) +
            float2(0.18, 0.18) * normalize(p + float2(0.0001)));
        shift += d * g;
    }

    if (bubble.z > bubble.x && bubbleRefraction > 0.0) {
        float2 bh = (bubble.zw - bubble.xy) * 0.5;
        float2 q = coord - (bubble.xy + bh);
        float br = min(bh.x, bh.y);
        float bsd = sdRoundRect(q, bh, br);
        if (-bsd < bubbleRefraction) {
            float inside = -min(bsd, 0.0);
            float d = arcLift(1.0 - inside / bubbleRefraction) * bubbleRefraction * 0.42;
            float2 g = normalize(gradRoundRect(q, bh, br) +
                float2(0.20) * normalize(q + float2(0.0001)));
            shift += d * g;
        }
    }

    float2 uv = (coord + shift) * contentScale;
    if (chrome <= 0.001 || abs(shift.x) + abs(shift.y) < 1.0) {
        return content.eval(uv);
    }
    float2 disp = shift * chrome;
    half4 c = half4(0.0);
    c.r = content.eval(uv + disp).r;
    c.g = content.eval(uv).g;
    c.b = content.eval(uv - disp).b;
    c.a = 1.0;
    return c;
}
"""

        /**
         * PrismalAGSL's `default_specular`: the rim light is derived from the SDF
         * gradient, so it only glows where the surface normal faces the light.
         */
        const val SPECULAR_AGSL = """
uniform float2 size;
uniform float radius;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

float sdRoundRect(float2 coord, float2 halfSize, float r) {
    float2 c = abs(coord) - (halfSize - float2(r));
    float outside = length(max(c, float2(0.0))) - r;
    float inside = min(max(c.x, c.y), 0.0);
    return outside + inside;
}

float2 gradRoundRect(float2 coord, float2 halfSize, float r) {
    float2 c = abs(coord) - (halfSize - float2(r));
    if (c.x >= 0.0 || c.y >= 0.0) {
        return sign(coord) * normalize(max(c, float2(0.0)));
    }
    float gx = step(c.y, c.x);
    return sign(coord) * float2(gx, 1.0 - gx);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float gr = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 g = gradRoundRect(p, halfSize, gr);
    float d = max(dot(g, float2(cos(angle), sin(angle))), 0.0);
    return color * pow(d, falloff);
}
"""

        /**
         * Soft, diffuse specular for droplet/capsule border (replaces hard white ring).
         * Cool blue-cyan-violet tint, falloff 2.5 for gentle falloff, low alpha.
         * Drawn with BlendMode.PLUS for additive glass-like highlight.
         */
        const val SOFT_SPECULAR_AGSL = """
float prismalCornerRadiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float prismalSdRoundRect(float2 coord, float2 halfSize, float radius) {
    float2 c = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(c, float2(0.0))) - radius;
    float inside = min(max(c.x, c.y), 0.0);
    return outside + inside;
}

float2 prismalGradSdRoundRect(float2 coord, float2 halfSize, float radius) {
    float2 c = abs(coord) - (halfSize - float2(radius));
    if (c.x >= 0.0 || c.y >= 0.0) {
        return sign(coord) * normalize(max(c, float2(0.0)));
    } else {
        float gx = step(c.y, c.x);
        return sign(coord) * float2(gx, 1.0 - gx);
    }
}

float prismalArcLift(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = prismalCornerRadiusAt(coord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = prismalGradSdRoundRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}
"""

    }

    private val captureHandler = Handler(Looper.getMainLooper())
    private var captureLoop: Runnable? = null

    private var pivotRef: WeakReference<ViewGroup>? = null
    private val watchedRoots = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val touchHooked = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var layoutParamsHooked = false
    private val docks = WeakHashMap<ViewGroup, LiquidDockDrawable>()
    @Volatile private var pivotBarId = 0
    @Volatile private var bottomContainerId = 0
    @Volatile private var paneContainerId = 0
    @Volatile private var reelFooterId = 0
    @Volatile private var reelTimeBarId = 0
    @Volatile private var selectedIndex = 0
    @Volatile private var injectedSelected = false
    private var dragStartX = 0f
    private var dragStartIndex = 0
    private var dragMoved = false
    private var touchSlopPx = -1


    private fun touchSlop(view: View): Int {
        if (touchSlopPx < 0) {
            touchSlopPx = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        }
        return touchSlopPx
    }

    public fun init(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) =
                        schedule(param.thisObject as? Activity)
                }
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) =
                        schedule(param.thisObject as? Activity)
                }
            )
            // Keep the dock alive: YouTube hides its bottom bar while scrolling.
            // We only block visibility changes; translation/scale are corrected in apply()
            // to avoid fighting YouTube's Shorts transition animations.
            XposedHelpers.findAndHookMethod(
                View::class.java, "setVisibility", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (view.id == pivotBarId || view.id == bottomContainerId) {
                            param.args[0] = View.VISIBLE
                        }
                    }
                }
            )
            try {
                XposedHelpers.findAndHookMethod(
                    View::class.java, "setBackgroundAlpha", Float::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (view.id == pivotBarId || view.id == bottomContainerId) {
                                param.args[0] = 1f
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
            }

            // Move the selection bubble to the tapped tab.
            XposedHelpers.findAndHookMethod(
                View::class.java, "performClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clicked = param.thisObject as? View ?: return
                        val bar = pivotRef?.get() ?: return
                        if (clicked === bar || !isInside(clicked, bar)) return
                        if (clicked.tag != TAG_SUBS_SLOT) injectedSelected = false
                        select(bar, indexForView(bar, clicked))
                    }
                }
            )

            hookLayoutParams(classLoader)

            XposedBridge.log("[InstaMod] YouTube floating navigation hook initialized (Liquid Glass).")
        } catch (t: Throwable) {
            XposedBridge.log("[InstaMod] YouTube hook failed: ${t.message}")
        }
    }

    private fun schedule(activity: Activity?) {
        val decor = activity?.window?.decorView ?: return
        if (watchedRoots.add(decor)) {
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                decor.post { apply(activity) }
            }
        }
        decor.postDelayed({ apply(activity) }, 250L)
    }

    private fun apply(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (pivotBarId == 0) pivotBarId = root.resources.getIdentifier("pivot_bar", "id", activity.packageName)
        val bar = (if (pivotBarId != 0) root.findViewById<View>(pivotBarId) else null) as? ViewGroup ?: return
        if (bottomContainerId == 0) bottomContainerId =
            root.resources.getIdentifier("bottom_bar_container", "id", activity.packageName)
        if (paneContainerId == 0) paneContainerId =
            root.resources.getIdentifier("pane_fragment_container", "id", activity.packageName)
        pivotRef = WeakReference(bar)

        // Let the feed scroll beneath the floating dock instead of stopping above it.
        var paneView: View? = null
        if (paneContainerId != 0) {
            root.findViewById<View>(paneContainerId)?.let { pane ->
                paneView = pane
                if (pane.paddingBottom != 0) {
                    pane.setPadding(pane.paddingLeft, pane.paddingTop, pane.paddingRight, 0)
                }
            }
        }

        // Shorts: the title/channel block sits right under the dock since the pane
        // no longer reserves space, so give its footer back the dock clearance.
        if (reelFooterId == 0) reelFooterId =
            root.resources.getIdentifier("reel_player_footer_container", "id", activity.packageName)
        if (reelFooterId != 0) {
            root.findViewById<View>(reelFooterId)?.let { footer ->
                val clearance = dp(activity.resources.displayMetrics.density, 64)
                if (footer.paddingBottom != clearance) {
                    footer.setPadding(
                        footer.paddingLeft, footer.paddingTop, footer.paddingRight, clearance
                    )
                }
            }
        }

        // Shorts: the progress bar is pinned to the very bottom of the display,
        // under the gesture area. Lift it just above the dock so it can be grabbed.
        if (reelTimeBarId == 0) reelTimeBarId =
            root.resources.getIdentifier("reel_time_bar", "id", activity.packageName)
        if (reelTimeBarId != 0) {
            root.findViewById<View>(reelTimeBarId)?.let { timeBar ->
                val dy = -dp(activity.resources.displayMetrics.density, 66).toFloat()
                if (timeBar.translationY != dy) timeBar.translationY = dy
            }
        }

        // The strip behind the floating capsule stays transparent so the feed keeps
        // scrolling underneath; only a soft fade melts it into the navigation area.
        if (bottomContainerId != 0) {
            root.findViewById<View>(bottomContainerId)?.let { container ->
                if (container.foreground != null) container.foreground = null
                if (container.elevation != 0f) container.elevation = 0f
                // Prevent capsule end clipping during drag
                if (container is ViewGroup) {
                    container.clipChildren = false
                    container.clipToPadding = false
                }
                val light = isLightTheme(container)
                val fade = container.background as? BottomFadeDrawable
                if (fade == null || fade.isLight != light) {
                    container.background = BottomFadeDrawable(light)
                }
            }
        }

        val density = activity.resources.displayMetrics.density
        val dock = docks.getOrPut(bar) { LiquidDockDrawable(density) }
        startBackdropCapture(bar, paneView, dock)

        // YouTube fades the bar's own background while scrolling (backgroundAlpha < 1).
        try {
            XposedHelpers.callMethod(bar, "setBackgroundAlpha", 1f)
        } catch (_: Throwable) {
        }
        // Never let YouTube shrink or translate the dock (Shorts tab used to scale/move it down).
        if (bar.scaleX != 1f) bar.scaleX = 1f
        if (bar.scaleY != 1f) bar.scaleY = 1f
        if (bar.translationY != 0f) bar.translationY = 0f

        if (bar.tag == TAG) {

            if (bar.background !== dock) bar.background = dock
            if (!bar.willNotDraw()) bar.setWillNotDraw(true)
            stripCreateCircle(bar)
            return
        }

        val lp = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val side = dp(density, 30)
        val bottom = dp(density, 20)
        val height = dp(density, 40)
        if (lp.leftMargin != side || lp.rightMargin != side ||
            lp.bottomMargin != bottom || lp.height != height
        ) {
            lp.setMargins(side, 0, side, bottom)
            lp.height = height
            bar.layoutParams = lp
        }

        bar.foreground = null
        bar.background = dock
        bar.elevation = dp(density, 8).toFloat()
        bar.clipToOutline = true
        // PivotBar custom-draws its own selection indicator on top of the background.
        // Our bubble replaces it, so let the container draw nothing.
        bar.setWillNotDraw(true)
        bar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        hideYoutubeLabels(bar)
        bar.tag = TAG
        stripCreateCircle(bar)
        injectSubscriptionsTab(bar, density)

        bar.post {
            val slots = slotCount(bar)
            dock.slots = slots
            dock.lightTheme = isLightTheme(bar)
            val detected = detectSelectedIndex(bar)
            if (detected >= 0) selectedIndex = detected
            selectedIndex = selectedIndex.coerceIn(0, slots - 1)
            dock.initializeSlot(selectedIndex, bar.width, bar.height)
            applySlotAlpha(bar, selectedIndex)
            centerIcons(bar)
            hookBarTouch(bar)
        }
    }

    /**
     * YouTube ships four bottom tabs on this build (Home, Shorts, Create, You) and
     * leaves Subscriptions out. We add it back as a native-looking fifth slot that
     * opens YouTube's own subscriptions feed, so the dock matches the 5-tab layout.
     */
    private fun injectSubscriptionsTab(bar: ViewGroup, density: Float) {
        val row = bar.getChildAt(0) as? android.widget.LinearLayout ?: return
        // Already injected (same row) or YouTube ships its own five tabs.
        val existing = if (row.childCount > SUBS_SLOT) row.getChildAt(SUBS_SLOT) else null
        if (existing?.tag == TAG_SUBS_SLOT) return
        if (row.childCount >= 5) return
        val ctx = bar.context
        val res = ctx.resources
        val pkg = ctx.packageName
        var iconId = res.getIdentifier(
            "yt_outline_experimental_subscriptions_vd_theme_24", "drawable", pkg
        )
        if (iconId == 0) iconId =
            res.getIdentifier("ic_shortcut_subscriptions", "drawable", pkg)
        if (iconId == 0) return

        val light = isLightTheme(bar)
        val iconColor = if (light) Color.rgb(15, 15, 15) else Color.WHITE
        val size = dp(density, 24)

        val icon = ImageView(ctx).apply {
            setImageResource(iconId)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            tag = TAG_SUBS_ICON
        }
        val holder = android.widget.FrameLayout(ctx).apply {
            addView(icon, android.widget.FrameLayout.LayoutParams(
                size, size, android.view.Gravity.CENTER
            ))
            contentDescription = "Subscriptions"
            isClickable = true
            tag = TAG_SUBS_SLOT
            setOnClickListener { openSubscriptions(ctx, bar, row) }
        }
        row.addView(
            holder, SUBS_SLOT,
            android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        )
        row.tag = TAG_SUBS
    }

    /** Keep the injected tab's glyph in sync with the light/dark theme. */
    private fun refreshSubscriptionsIcon(bar: ViewGroup, light: Boolean) {
        val row = bar.getChildAt(0) as? ViewGroup ?: return
        val holder = row.getChildAt(SUBS_SLOT) as? ViewGroup ?: return
        if (holder.tag != TAG_SUBS_SLOT) return
        val icon = holder.getChildAt(0) as? ImageView ?: return
        val color = if (light) Color.rgb(15, 15, 15) else Color.WHITE
        icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    /** Hand the subscriptions feed to YouTube itself: no reimplementation. */    private fun openSubscriptions(
        ctx: android.content.Context, bar: ViewGroup, row: ViewGroup
    ) {
        injectedSelected = true
        select(bar, SUBS_SLOT)
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/feed/subscriptions")
            ).apply {
                setPackage(ctx.packageName)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    /** Native selected tab, or -1 while YouTube has not flagged one yet. */
    private fun detectSelectedIndex(bar: ViewGroup): Int {
        val row = bar.getChildAt(0) as? ViewGroup ?: return -1
        for (i in 0 until row.childCount) {
            if (row.getChildAt(i).isSelected) return i
        }
        return -1
    }

    /** YouTube pins the icons to the bottom of the slot (labels sit under them).
     *  With the labels hidden and a shorter bar they must be re-centred. */
    private fun findIcon(v: View): ImageView? {
        if (v is ImageView && v.visibility == View.VISIBLE && v.width > 0 && v.height > 0) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val found = findIcon(v.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    private fun centerIcons(bar: ViewGroup) {
        val row = bar.getChildAt(0) as? ViewGroup ?: return
        row.clipChildren = false
        row.clipToPadding = false
        bar.clipChildren = false
        bar.clipToPadding = false
        val barPos = IntArray(2)
        bar.getLocationInWindow(barPos)
        val target = barPos[1] + bar.height / 2f
        for (i in 0 until row.childCount) {
            val slot = row.getChildAt(i) as? ViewGroup ?: continue
            slot.clipChildren = false
            slot.clipToPadding = false
            val icon = findIcon(slot)
            if (icon == null) continue
            // Hook setTranslationY to prevent YouTube from resetting our centering.
            if (icon.tag != "InstaModTranslationHooked") {
                icon.tag = "InstaModTranslationHooked"
                try {
                    XposedHelpers.findAndHookMethod(
                        View::class.java, "setTranslationY", Float::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (param.thisObject !== icon) return
                                val newY = param.args[0] as Float
                                val pos = IntArray(2)
                                icon.getLocationInWindow(pos)
                                val baseCenter = pos[1] - icon.translationY + icon.height / 2f
                                val dy = target - baseCenter
                                if (abs(newY - dy) > 2f) {
                                    // YouTube is trying to reset it; keep our value.
                                    param.args[0] = dy
                                }
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }
            val pos = IntArray(2)
            icon.getLocationInWindow(pos)
            val baseCenter = pos[1] - icon.translationY + icon.height / 2f
            val dy = target - baseCenter
            if (abs(icon.translationY - dy) > 0.5f) {
                icon.translationY = dy
            }
        }
    }

    private fun isLightTheme(view: View): Boolean =
        (view.context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_NO

    /** YouTube ships 4 or 5 tabs depending on the account: never hardcode it. */
    private fun slotCount(bar: ViewGroup): Int {
        val row = bar.getChildAt(0) as? ViewGroup ?: return DEFAULT_SLOTS
        return row.childCount.coerceAtLeast(2)
    }

    private fun handleBarTouch(bar: ViewGroup, event: MotionEvent) {
        val dock = docks[bar] ?: return
        val slots = slotCount(bar)
        val slotWidth = bar.width / slots.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartIndex = selectedIndex
                dragMoved = false
                dock.beginDrag(selectedIndex, bar.width, bar.height)
                dock.dragTo(event.x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - dragStartX) > touchSlop(bar)) dragMoved = true
                if (dragMoved) dock.dragTo(event.x)
            }
            MotionEvent.ACTION_UP -> {
                val index = (event.x / slotWidth).toInt().coerceIn(0, slots - 1)
                val moved = dragMoved
                dragMoved = false
                dock.endDrag()
                dock.animateToSlot(index, bar.width, bar.height)
                // Dragging across the bar behaves like tapping the released tab.
                if (moved && index != dragStartIndex) {
                    selectedIndex = index
                    applySlotAlpha(bar, index)
                    (bar.getChildAt(0) as? ViewGroup)?.getChildAt(index)?.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragMoved = false
                dock.endDrag()
                dock.animateToSlot(selectedIndex, bar.width, bar.height)
            }
        }
    }

    /**
     * The "create" slot paints a 101dp dark circle behind its 68dp plus icon
     * (a two-layer drawable). Hide the circle layer so it matches the other tabs.
     */
    private fun stripCreateCircle(bar: ViewGroup) {
        val row = bar.getChildAt(0) as? ViewGroup ?: return
        val slot = row.getChildAt(CREATE_SLOT) as? ViewGroup ?: return

        val iconColor = if ((bar.context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        ) Color.rgb(15, 15, 15) else Color.WHITE

        fun walk(v: View) {
            // The slot's own ripple would paint a second disc on press.
            if (v.background != null) v.background = null
            if (v is ImageView) {
                val drawable = v.drawable
                val layers = drawable as? LayerDrawable
                if (layers != null) {
                    for (i in 0 until layers.numberOfLayers) {
                        val layer = layers.getDrawable(i) ?: continue
                        if (layer is GradientDrawable && layer.alpha != 0) layer.alpha = 0
                    }
                } else if (drawable != null) {
                    // New layouts ship the "+" inside a ring (StateListDrawable):
                    // draw our own bare plus so only the glyph remains.
                    v.scaleType = ImageView.ScaleType.CENTER
                    v.setPadding(0, 0, 0, 0)
                    v.setImageDrawable(
                        YoutubeFloatingNavHook.PlusDrawable(
                            iconColor, dp(v.resources.displayMetrics.density, 26)
                        )
                    )
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(slot)
    }

    /** YouTube re-applies its native 48dp height after us: keep forcing ours. */
    private fun hookLayoutParams(classLoader: ClassLoader) {
        if (layoutParamsHooked) return
        layoutParamsHooked = true
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setLayoutParams", ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (pivotBarId == 0) return
                        val view = param.thisObject as? View ?: return
                        if (view.id != pivotBarId) return
                        val lp = param.args[0] as? ViewGroup.MarginLayoutParams ?: return
                        val d = view.resources.displayMetrics.density
                        lp.setMargins(dp(d, 30), 0, dp(d, 30), dp(d, 20))
                        lp.height = dp(d, 40)
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Real backdrop blur: the dock lives in the same window as the feed, so
    // there is no "blur what is behind me" API. Instead we re-draw the pane
    // fragment (which does NOT contain the dock) into a tiny bitmap at a low
    // rate, blur it, and use it as the capsule fill.
    // ------------------------------------------------------------------
    private fun startBackdropCapture(bar: ViewGroup, pane: View?, dock: LiquidDockDrawable) {
        if (pane == null || captureLoop != null) return
        val barRef = WeakReference(bar)
        val paneRef = WeakReference(pane)
        val runnable = object : Runnable {
            private var lastLight = isLightTheme(bar)
            override fun run() {
                val b = barRef.get()
                val p = paneRef.get()
                if (b == null || p == null) {
                    captureLoop = null
                    return
                }
                try {
                    if (b.isAttachedToWindow && p.isAttachedToWindow &&
                        b.width > 0 && b.height > 0 && b.alpha > 0f
                    ) {
                        val w = (b.width * CAPTURE_SCALE).toInt().coerceAtLeast(8)
                        val h = (b.height * CAPTURE_SCALE).toInt().coerceAtLeast(4)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val barPos = IntArray(2)
                        val panePos = IntArray(2)
                        b.getLocationInWindow(barPos)
                        p.getLocationInWindow(panePos)
                        val canvas = Canvas(bmp)
                        canvas.scale(CAPTURE_SCALE, CAPTURE_SCALE)
                        canvas.translate(
                            (panePos[0] - barPos[0]).toFloat(),
                            (panePos[1] - barPos[1]).toFloat()
                        )
                        p.draw(canvas)
                        val light = isLightTheme(b)
                        if (light != lastLight) {
                            // Theme flipped while running: repaint the create glyph too.
                            lastLight = light
                            stripCreateCircle(b)
                            refreshSubscriptionsIcon(b, light)
                        }
                        dock.lightTheme = light
                        centerIcons(b)
                        if (!dock.isDragging && !injectedSelected) {
                            val detected = detectSelectedIndex(b)
                            if (detected >= 0 && detected != selectedIndex &&
                                detected < (b.getChildAt(0) as? ViewGroup)?.childCount.orZero()
                            ) {
                                selectedIndex = detected
                                dock.slots = slotCount(b)
                                applySlotAlpha(b, detected)
                                dock.animateToSlot(detected, b.width, b.height)
                            }
                        }
                        val luminance = blurAndMeasure(bmp, BLUR_RADIUS)
                        // SurfaceView content (Shorts) renders empty: keep the static glass.
                        dock.setBackdrop(if (luminance > 3) bmp else null, luminance)
                    }
                } catch (_: Throwable) {
                }
                captureHandler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }
        captureLoop = runnable
        captureHandler.postDelayed(runnable, 200L)
    }

    /** Two-pass separable box blur; returns the mean luminance of the source. */
    private fun blurAndMeasure(bitmap: Bitmap, radius: Int): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        for (p in pixels) {
            sum += (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        boxBlur(pixels, w, h, radius)
        boxBlur(pixels, w, h, radius)
        for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return (sum / pixels.size).toInt()
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val div = radius * 2 + 1
        val tmp = IntArray(pixels.size)
        for (y in 0 until h) {
            val row = y * w
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = pixels[row + i.coerceIn(0, w - 1)]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = pixels[row + (x - radius).coerceIn(0, w - 1)]
                val inx = pixels[row + (x + radius + 1).coerceIn(0, w - 1)]
                r += ((inx shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inx shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inx and 0xFF) - (out and 0xFF)
            }
        }
        for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until h) {
                pixels[y * w + x] = ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                val inx = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                r += ((inx shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inx shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inx and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** PivotBar overrides dispatchTouchEvent, so hook the concrete class. */
    private fun hookBarTouch(bar: ViewGroup) {
        if (!touchHooked.add(bar)) return
        var cls: Class<*>? = bar.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val method = cls.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== bar) return
                        val event = param.args[0] as? MotionEvent ?: return
                        handleBarTouch(bar, event)
                    }
                })
                return
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
    }

    private fun select(bar: ViewGroup, index: Int) {
        if (index == selectedIndex) {
            docks[bar]?.animateToSlot(index, bar.width, bar.height)
            return
        }
        selectedIndex = index
        docks[bar]?.animateToSlot(index, bar.width, bar.height)
        applySlotAlpha(bar, index)
    }

    private fun applySlotAlpha(bar: ViewGroup, index: Int) {
        val row = bar.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            child.animate().cancel()
            child.alpha = if (i == index) 1f else 0.92f
        }
    }

    private fun indexForView(bar: ViewGroup, view: View): Int {
        val rect = Rect(0, 0, view.width, view.height)
        bar.offsetDescendantRectToMyCoords(view, rect)
        return (rect.exactCenterX() / (bar.width / slotCount(bar).toFloat()))
            .toInt().coerceIn(0, slotCount(bar) - 1)
    }

    private fun isInside(view: View, parent: ViewGroup): Boolean {
        var node: View? = view
        while (node != null && node !== parent) node = node.parent as? View
        return node === parent
    }

    private fun hideYoutubeLabels(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView) {
                child.visibility = View.GONE
            } else if (child is ViewGroup) {
                hideYoutubeLabels(child)
            }
        }
    }

    private fun dp(density: Float, value: Int): Int = (value * density).toInt()

    private fun Int?.orZero(): Int = this ?: 0

    /**
     * Soft fade from transparent to the page background, painted in the strip that
     * sits behind the floating dock. The feed keeps scrolling underneath, then melts
     * into the system navigation area instead of ending on a hard edge.
     */
    private class BottomFadeDrawable(private val lightTheme: Boolean) : Drawable() {

        val isLight: Boolean get() = lightTheme

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: LinearGradient? = null
        private var shaderHeight = -1

        override fun draw(canvas: Canvas) {
            val h = bounds.height()
            if (h <= 0) return
            var sh = shader
            if (sh == null || shaderHeight != h) {
                val page = if (lightTheme) Color.WHITE else Color.rgb(15, 15, 15)
                sh = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(
                        Color.argb(0, 0, 0, 0),
                        Color.argb(0, 0, 0, 0),
                        Color.argb(0xC0, Color.red(page), Color.green(page), Color.blue(page)),
                        page
                    ),
                    floatArrayOf(0f, 0.62f, 0.88f, 1f),
                    Shader.TileMode.CLAMP
                )
                shader = sh
                shaderHeight = h
            }
            paint.shader = sh
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Bare rounded "+" glyph used to replace the ringed create icon. */    private class PlusDrawable(color: Int, private val sizePx: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }

        override fun getIntrinsicWidth(): Int = sizePx

        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) setBounds(0, 0, sizePx, sizePx)
            val b = bounds
            if (b.width() == 0 || b.height() == 0) return
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val arm = b.width() * 0.30f
            paint.strokeWidth = b.width() * 0.085f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    // =========================================================================
    // Liquid Glass capsule
    // =========================================================================
    private class LiquidDockDrawable(private val density: Float) : Drawable() {

        private val capsule = RectF()
        private val bubble = RectF()
        private val bubbleDraw = RectF()
        private var width = 0
        private var height = 0
        private var radius = 0f
        private var current = 0
        private var dragging = false
        private var animator: ValueAnimator? = null
        private var shader: LinearGradient? = null
        private var shaderHeight = -1
        private var shaderLight = false
        private var refractionShader: android.graphics.RuntimeShader? = null
        private var refractionBroken = false
        private var specular: android.graphics.RuntimeShader? = null
        private var specularBroken = false
        private var specularKey = ""
        private var bubbleSoftSpecular: android.graphics.RuntimeShader? = null
        private var bubbleSoftSpecularBroken = false
        private var bubbleSoftSpecularKey = ""
        private var capsuleSoftSpecular: android.graphics.RuntimeShader? = null
        private var capsuleSoftSpecularBroken = false
        private var capsuleSoftSpecularKey = ""

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // Navy/dark translucent glass capsule colors (not white/milky) — bleu nuit translucide
        private val glassTop = Color.argb(140, 10, 14, 30)    // ~55% alpha, deep navy
        private val glassBottom = Color.argb(160, 6, 10, 24)   // ~63% alpha, deeper navy
        // Subtle cool rim — no hard white ring
        private val rimTop = Color.argb(40, 160, 190, 255)     // soft cyan-blue
        private val rimMid = Color.argb(8, 120, 160, 255)      // very subtle
        private val rimBottom = Color.argb(4, 80, 120, 255)    // barely there
        // Lens rim — soft blue-cyan glow for the active bubble
        private val lensRimTop = Color.argb(50, 100, 180, 255)
        private val lensRimBottom = Color.argb(0, 100, 180, 255)

        /** The glass must follow the app colour scheme: milky white in light mode. */
        @Volatile var lightTheme = false

        @Volatile private var backdrop: Bitmap? = null
        @Volatile private var backdropLuminance = 96
        private var tintShader: LinearGradient? = null
        private var tintHeight = -1
        private var tintAlphaCached = -1
        private var tintLightCached = false

        /** Blurred snapshot of whatever sits behind the dock. */
        fun setBackdrop(bitmap: Bitmap?, luminance: Int) {
            backdrop = bitmap
            // Smooth the luminance so the lens never flickers between dark and light.
            val target = luminance.coerceIn(0, 255)
            backdropLuminance = (backdropLuminance * 3 + target) / 4
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (width == 0 || height == 0) return

            // 1. Glass: blurred backdrop + a light tint (or the static gradient as fallback)
            val blur = backdrop
            if (blur != null && !blur.isRecycled) {
                fill.shader = refractiveShader(blur)
                fill.alpha = 255
                canvas.drawRoundRect(capsule, radius, radius, fill)
                fill.shader = tintGradient(height)
                canvas.drawRoundRect(capsule, radius, radius, fill)
            } else {
                fill.shader = shader(height)
                // Paint alpha modulates the shader; reset it or the lens colour leaks in.
                fill.alpha = 255
                canvas.drawRoundRect(capsule, radius, radius, fill)
            }
            fill.shader = null

            // 3. Selection lens: crisp capsule, dark over bright pages, light over dark ones
            if (bubble.width() > 0f) {
                bubbleDraw.set(bubble)
                if (dragging) bubbleDraw.inset(-dp(2f), -dp(2f))
                val br = bubbleDraw.height() * 0.5f
                val darkLens = !lightTheme && glassLuminance() >= LENS_DARK_THRESHOLD
                fill.shader = lensGradient(darkLens, bubbleDraw)
                canvas.drawRoundRect(bubbleDraw, br, br, fill)
                fill.shader = null

                // Soft, diffuse specular border for the droplet (replaces hard white ring).
                // Soft blue-cyan-violet glow, falloff 2.5 for gentle falloff, low alpha.
                val spec = bubbleSoftSpecularShader(bubbleDraw, br)
                if (spec != null) {
                    canvas.save()
                    canvas.translate(bubbleDraw.left, bubbleDraw.top)
                    stroke.shader = spec
                    stroke.blendMode = android.graphics.BlendMode.PLUS
                    stroke.strokeWidth = dp(1.5f)
                    canvas.drawRoundRect(0f, 0f, bubbleDraw.width(), bubbleDraw.height(), br, br, stroke)
                    stroke.shader = null
                    stroke.blendMode = android.graphics.BlendMode.SRC_OVER
                    canvas.restore()
                } else {
                    // Fallback: very subtle soft edge with low alpha — cyan-blue tint.
                    stroke.blendMode = android.graphics.BlendMode.SRC_OVER
                    stroke.shader = null
                    stroke.color = Color.argb(if (lightTheme) 50 else 75, 100, 170, 255)
                    stroke.strokeWidth = dp(1.0f)
                    canvas.drawRoundRect(bubbleDraw, br, br, stroke)
                }
            }

            // Soft, diffuse specular border for the capsule (replaces hard white ring).
            // Soft blue-cyan-violet glow, falloff 2.5 for gentle falloff, low alpha.
            val spec = capsuleSoftSpecularShader()
            if (spec != null) {
                canvas.save()
                stroke.shader = spec
                stroke.blendMode = android.graphics.BlendMode.PLUS
                stroke.strokeWidth = dp(1.5f)
                canvas.drawRoundRect(capsule, radius, radius, stroke)
                stroke.shader = null
                stroke.blendMode = android.graphics.BlendMode.SRC_OVER
                canvas.restore()
            } else {
                // Fallback: very subtle soft edge with low alpha — cyan-blue tint.
                stroke.blendMode = android.graphics.BlendMode.SRC_OVER
                stroke.shader = null
                stroke.color = Color.argb(if (lightTheme) 35 else 50, 90, 160, 255)
                stroke.strokeWidth = dp(1.0f)
                canvas.drawRoundRect(capsule, radius, radius, stroke)
            }
        }

        /**
         * PrismalAGSL-style edge refraction. Falls back to the plain blurred bitmap on
         * devices without AGSL (API < 33) or if the shader fails to compile.
         */
        private fun refractiveShader(blur: Bitmap): Shader {
            val plain = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (android.os.Build.VERSION.SDK_INT < 33 || refractionBroken) return plain
            return try {
                var sh = refractionShader
                if (sh == null) {
                    sh = android.graphics.RuntimeShader(REFRACTION_AGSL)
                    refractionShader = sh
                }
                sh.setInputShader("content", plain)
                sh.setFloatUniform("size", width.toFloat(), height.toFloat())
                sh.setFloatUniform(
                    "contentScale",
                    blur.width.toFloat() / width,
                    blur.height.toFloat() / height
                )
                sh.setFloatUniform("radius", radius)
                sh.setFloatUniform("refractionHeight", height * REFRACT_BAND)
                sh.setFloatUniform("refractionAmount", -height * REFRACT_AMOUNT)
                sh.setFloatUniform("chrome", REFRACT_CHROME)
                val b = bubbleDraw.takeIf { it.width() > 0f } ?: bubble
                sh.setFloatUniform("bubble", b.left, b.top, b.right, b.bottom)
                // Droplet refractionHeight ~12dp, refractionAmount ~6dp (reduced from 20/10)
                sh.setFloatUniform("bubbleRefraction", b.height() * 0.5f * REFRACT_BAND * 4.0f)
                sh
            } catch (_: Throwable) {
                refractionBroken = true
                plain
            }
        }

        /** Soft, diffuse specular border for the droplet (replaces hard white ring).
         * Soft blue-cyan-violet glow, falloff 2.5 for gentle falloff, low alpha.
         * Drawn with BlendMode.PLUS for additive glass-like highlight. */
        private fun bubbleSoftSpecularShader(r: RectF, br: Float): Shader? {
            if (android.os.Build.VERSION.SDK_INT < 33) return null
            val key = "\${r.width().toInt()}x\${r.height().toInt()}|\$lightTheme|soft"
            return try {
                var sh = bubbleSoftSpecular
                if (sh == null) {
                    sh = android.graphics.RuntimeShader(SOFT_SPECULAR_AGSL)
                    bubbleSoftSpecular = sh
                }
                if (bubbleSoftSpecularKey != key) {
                    bubbleSoftSpecularKey = key
                    sh.setFloatUniform("size", r.width(), r.height())
                    sh.setFloatUniform("cornerRadii", br, br, br, br)
                    sh.setColorUniform("color", Color.argb(85, 100, 170, 255))
                    sh.setFloatUniform("angle", 0.7853982f)
                    sh.setFloatUniform("falloff", 2.5f)
                }
                sh
            } catch (_: Throwable) {
                null
            }
        }

        /** Soft, diffuse specular border for the capsule (replaces hard white ring).
         * Soft blue-cyan-violet glow, falloff 2.5 for gentle falloff, low alpha.
         * Drawn with BlendMode.PLUS for additive glass-like highlight. */
        private fun capsuleSoftSpecularShader(): Shader? {
            if (android.os.Build.VERSION.SDK_INT < 33) return null
            val key = "\${width}x\${height}|\$lightTheme|capsuleSoft"
            return try {
                var sh = capsuleSoftSpecular
                if (sh == null) {
                    sh = android.graphics.RuntimeShader(SOFT_SPECULAR_AGSL)
                    capsuleSoftSpecular = sh
                }
                if (capsuleSoftSpecularKey != key) {
                    capsuleSoftSpecularKey = key
                    sh.setFloatUniform("size", width.toFloat(), height.toFloat())
                    sh.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                    sh.setColorUniform("color", Color.argb(55, 90, 160, 255))
                    sh.setFloatUniform("angle", 0.7853982f)
                    sh.setFloatUniform("falloff", 2.5f)
                }
                sh
            } catch (_: Throwable) {
                null
            }
        }

        private fun shader(h: Int): LinearGradient {
            val cached = shader
            if (cached != null && shaderHeight == h && shaderLight == lightTheme) return cached
            // Navy/dark translucent glass capsule for BOTH themes — bleu nuit translucide
            val (top, bottom) = glassTop to glassBottom
            return LinearGradient(
                0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP
            ).also { shader = it; shaderHeight = h; shaderLight = lightTheme }
        }

        /**
         * The darker the backdrop, the lighter the tint. Over bright pages this keeps the
         * white icons readable, over dark content the glass stays almost transparent.
         */
        private fun tintAlpha(): Int {
            val lum = backdropLuminance
            return (138 + lum / 255f * 24f).toInt().coerceIn(132, 164)
        }

        /** Luminance of the glass as actually rendered (blur + tint), not of the raw content. */
        private fun glassLuminance(): Int {
            val a = tintAlpha()
            return (backdropLuminance * (255 - a) + 14 * a) / 255
        }

        private fun tintGradient(h: Int): LinearGradient {
            val light = lightTheme
            val alpha = if (light) {
                (128 + backdropLuminance / 255f * 20f).toInt().coerceIn(124, 150)
            } else {
                tintAlpha()
            }
            val cached = tintShader
            if (cached != null && tintHeight == h && tintAlphaCached == alpha &&
                tintLightCached == light
            ) return cached
            val top: Int
            val bottom: Int
            if (light) {
                // Navy/dark translucent tint for light theme too — bleu nuit translucide
                // Keeps the dock navy on light backgrounds, icons stay readable
                top = Color.argb(alpha, 14, 18, 38)
                bottom = Color.argb((alpha * 1.06f).toInt().coerceAtMost(255), 8, 12, 28)
            } else {
                // Navy/dark translucent tint for dark theme — bleu nuit translucide
                // Slightly higher alpha than glass for readable icons, but stays navy
                top = Color.argb(alpha, 14, 18, 38)
                bottom = Color.argb((alpha * 1.06f).toInt().coerceAtMost(255), 8, 12, 28)
            }
            return LinearGradient(
                0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP
            ).also {
                tintShader = it; tintHeight = h; tintAlphaCached = alpha; tintLightCached = light
            }
        }

        private var shadeShader: LinearGradient? = null
        private var shadeKey = ""

        /** Faint darkening towards the bottom edge, for volume. */
        private fun innerShadeGradient(): LinearGradient {
            val key = height.toString() + "|" + lightTheme
            val cached = shadeShader
            if (cached != null && shadeKey == key) return cached
            val alpha = if (lightTheme) 12 else 26
            return LinearGradient(
                0f, height * 0.55f, 0f, height.toFloat(),
                Color.argb(0, 0, 0, 0),
                Color.argb(alpha, 0, 0, 0),
                Shader.TileMode.CLAMP
            ).also { shadeShader = it; shadeKey = key }
        }

        private var lensShader: LinearGradient? = null
        private var lensKey = ""

        /**
         * Lens fill as a soft vertical gradient rather than a flat slab, so the
         * selection reads as a piece of glass sitting on the bar.
         */
        private fun lensGradient(dark: Boolean, r: RectF): LinearGradient {
            val top = r.top.toInt()
            val bottom = r.bottom.toInt()
            val key = dark.toString() + "|" + top + "|" + bottom + "|" + lightTheme
            val cached = lensShader
            if (cached != null && lensKey == key) return cached
            val alphas: Pair<Int, Int>
            val c: Int
            when {
                lightTheme -> { alphas = 168 to 122; c = 0xFFFFFF }       // bright glass bubble (white)
                dark -> { alphas = 70 to 40; c = 0x000000 }               // dark lens on bright content (black)
                else -> { alphas = 68 to 42; c = 0x5A7BB8 }               // blue-gray lens on dark content
            }
            val topColor = Color.argb(alphas.first, Color.red(c), Color.green(c), Color.blue(c))
            val bottomColor = Color.argb(alphas.second, Color.red(c), Color.green(c), Color.blue(c))
            return LinearGradient(
                0f, r.top, 0f, r.bottom, topColor, bottomColor, Shader.TileMode.CLAMP
            ).also { lensShader = it; lensKey = key }
        }



        fun initializeSlot(index: Int, w: Int, h: Int) {
            current = index
            if (w > 0 && h > 0) {
                width = w
                height = h
                capsule.set(0f, 0f, w.toFloat(), h.toFloat())
                radius = h / 2f
            }
            if (width > 0 && height > 0) {
                bubble.set(slotBounds(current, width, height))
            }
            invalidateSelf()
        }

        fun beginDrag(index: Int, w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            width = w
            height = h
            capsule.set(0f, 0f, w.toFloat(), h.toFloat())
            radius = h / 2f
            current = index
            if (bubble.width() <= 0f) bubble.set(slotBounds(index, w, h))
            animator?.cancel()
            dragging = true
            invalidateSelf()
        }

        fun dragTo(centerX: Float) {
            if (width <= 0 || height <= 0 || bubble.width() <= 0f) return
            val insetX = dp(7f)
            val half = bubble.width() / 2f
            val minLeft = insetX
            val maxLeft = (width - insetX - bubble.width()).coerceAtLeast(minLeft)
            val left = (centerX - half).coerceIn(minLeft, maxLeft)
            bubble.set(left, bubble.top, left + bubble.width(), bubble.bottom)
            invalidateSelf()
        }

        val isDragging: Boolean get() = dragging

        fun endDrag() {
            dragging = false
            invalidateSelf()
        }

        fun animateToSlot(index: Int, w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            if (w != width || h != height) {
                width = w
                height = h
                capsule.set(0f, 0f, w.toFloat(), h.toFloat())
                radius = h / 2f
            }
            current = index
            val to = slotBounds(index, width, height)
            val from = RectF(bubble)
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { value ->
                    val t = value.animatedValue as Float
                    bubble.set(
                        from.left + (to.left - from.left) * t,
                        from.top + (to.top - from.top) * t,
                        from.right + (to.right - from.right) * t,
                        from.bottom + (to.bottom - from.bottom) * t
                    )
                    invalidateSelf()
                }
                start()
            }
        }

        var slots = DEFAULT_SLOTS

        private fun slotBounds(index: Int, w: Int, h: Int): RectF {
            val slot = w / slots.toFloat()
            val insetX = dp(1f)
            val insetY = dp(4f)
            return RectF(
                index * slot + insetX,
                insetY,
                (index + 1) * slot - insetX,
                h - insetY
            )
        }

        override fun onBoundsChange(bounds: Rect) {
            width = bounds.width()
            height = bounds.height()
            capsule.set(0f, 0f, width.toFloat(), height.toFloat())
            radius = height / 2f
            bubble.set(slotBounds(current, width, height))
            invalidateSelf()
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(0, 0, width, height, radius)
        }

        // YouTube fades its bottom bar background (alpha ~0.26) while scrolling.
        // Ignore that so the Liquid Glass keeps a constant opacity.
        override fun setAlpha(alpha: Int) {
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        private fun dp(value: Float): Float = value * density
    }
}
