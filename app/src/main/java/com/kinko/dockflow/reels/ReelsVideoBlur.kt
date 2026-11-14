package com.kinko.dockflow.reels

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Verre sur les vidéos de Reels.
 *
 * Porté de igglass (maciasdav/igglass, MIT), lui-même appuyé sur les méthodes
 * cachées de SurfaceControl.
 *
 * Les vidéos Instagram sont dans des SurfaceView: le compositeur du système les
 * peint dans une couche à part, sous la fenêtre, et l'app ne laisse qu'un trou
 * transparent à cet endroit. Une capture de vue ne voit donc que le trou, pas la
 * vidéo: sous la capsule, le vidéo resterait net.
 *
 * Pour chaque SurfaceView qui passe sous la capsule on crée une couche enfant du
 * compositeur en forme de capsule avec un flou de fond: le compositeur floute la
 * vidéo qu'il a dessous. Sur une vidéo il n'y a pas de lentille: le compositeur
 * ne sait que flouter.
 *
 * Méthodes cachées utilisées: setEffectLayer, setBackgroundBlurRadius,
 * setCornerRadius. Si elles manquent, la partie se désactive et la vidéo reste
 * nette — aucun crash.
 */
internal class ReelsVideoBlur(
    private val bar: View,
    /** La capsule en coordonnées de la barre (sans transformation). */
    private val capsuleLocal: () -> RectF,
) {

    private class Layer(val parent: SurfaceControl, val child: SurfaceControl) {
        val crop = Rect()
        var visible = false
    }

    private val layers = WeakHashMap<SurfaceView, Layer>()
    private val density = bar.resources.displayMetrics.density

    private val capsuleWindow = RectF()
    private val crop = Rect()
    private val loc = IntArray(2)

    /** Après le premier échec on désactive: la vidéo redevient nette, sans plus. */
    private var failed = false
    private var lastUpdateUptime = 0L

    /** Poses de couches réussies (une seule ligne de log, pour le diagnostic). */
    private var loggedFirstLayer = false

    /** Pose les couches de flou sous la capsule. Appelé à chaque OnPreDraw. */
    fun update() {
        if (failed || !Hidden.available || ReelsParams.VIDEO_BLUR_RADIUS_DP <= 0f) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUpdateUptime < 16L) return
        lastUpdateUptime = now
        try {
            updateLayers()
        } catch (t: Throwable) {
            failed = true
            ReelsLog.w("le flou vidéo a échoué, désactivation", t)
            release()
        }
    }

    private fun updateLayers() {
        // Vidéos disparues: libérer leurs couches.
        val alive = Registry.surfaces()
        layers.keys.filter { it !in alive }.forEach { sv -> layers.remove(sv)?.let(::release) }

        val root = bar.rootSurfaceControl ?: return
        if (!locateCapsule()) {
            hideAll(root)
            return
        }
        val radius = capsuleWindow.height() / 2f
        val blur = (ReelsParams.VIDEO_BLUR_RADIUS_DP * density).roundToInt()

        val t = SurfaceControl.Transaction()
        var dirty = false
        for (sv in alive) {
            val parent = sv.surfaceControl?.takeIf {
                it.isValid && sv.isShown && sv.rootSurfaceControl === root
            }
            var layer = layers[sv]
            if (parent == null) {
                if (layer != null && layer.visible) {
                    t.setVisibility(layer.child, false)
                    layer.visible = false
                    dirty = true
                }
                continue
            }
            if (layer == null || layer.parent !== parent) {
                layer?.let { release(it) }
                layer = Layer(parent, Hidden.createEffectLayer(parent))
                layers[sv] = layer
            }

            // La capsule en coordonnées de la surface du vidéo. Si le vidéo est
            // agrandi (Reels plein écran), la couche fille hérite de l'échelle:
            // il faut la défaire.
            sv.getLocationInWindow(loc)
            val (sx, sy) = scaleOf(sv)
            crop.set(
                ((capsuleWindow.left - loc[0]) / sx).roundToInt(), ((capsuleWindow.top - loc[1]) / sy).roundToInt(),
                ((capsuleWindow.right - loc[0]) / sx).roundToInt(), ((capsuleWindow.bottom - loc[1]) / sy).roundToInt(),
            )
            val overlaps = crop.intersects(0, 0, sv.width, sv.height)
            if (!overlaps) {
                if (layer.visible) {
                    t.setVisibility(layer.child, false)
                    layer.visible = false
                    dirty = true
                }
                continue
            }
            if (!layer.visible || crop != layer.crop) {
                t.setCrop(layer.child, crop)
                t.setLayer(layer.child, 1)
                Hidden.setCornerRadius(t, layer.child, radius / sy)
                Hidden.setBackgroundBlurRadius(t, layer.child, blur)
                t.setVisibility(layer.child, true)
                layer.crop.set(crop)
                layer.visible = true
                dirty = true
                if (!loggedFirstLayer) {
                    loggedFirstLayer = true
                    ReelsLog.i("couche de flou posée: crop=$crop blur=${blur}px rayon=${radius / sy}")
                }
            }
        }
        if (dirty) root.applyTransactionOnDraw(t) else t.close()
    }

    /** Échelle accumulée de [view] et de ses ancêtres. */
    private fun scaleOf(view: View): Pair<Float, Float> {
        var sx = 1f
        var sy = 1f
        var v: Any? = view
        while (v is View) {
            sx *= v.scaleX
            sy *= v.scaleY
            v = v.parent
        }
        return sx to sy
    }

    /** Retire toutes les couches. */
    fun release() {
        layers.values.forEach(::release)
        layers.clear()
    }

    private fun release(layer: Layer) {
        ReelsLog.safe("libération couche de flou") {
            SurfaceControl.Transaction().reparent(layer.child, null).apply()
            layer.child.release()
        }
    }

    private fun hideAll(root: android.view.AttachedSurfaceControl) {
        val visible = layers.values.filter { it.visible }
        if (visible.isEmpty()) return
        val t = SurfaceControl.Transaction()
        visible.forEach {
            t.setVisibility(it.child, false)
            it.visible = false
        }
        root.applyTransactionOnDraw(t)
    }

    /** La capsule en coordonnées fenêtre, avec la transformation actuelle de la barre. */
    private fun locateCapsule(): Boolean {
        if (!bar.isShown || bar.width <= 0 || bar.height <= 0) return false
        val parent = bar.parent as? View ?: return false
        capsuleWindow.set(capsuleLocal())
        if (capsuleWindow.isEmpty) return false
        val matrix: Matrix = bar.matrix
        if (!matrix.isIdentity) matrix.mapRect(capsuleWindow)
        parent.getLocationInWindow(loc)
        capsuleWindow.offset((loc[0] + bar.left).toFloat(), (loc[1] + bar.top).toFloat())
        return true
    }

    /** SurfaceView vivantes; maintenues par les hooks de ReelsHook. */
    object Registry {
        private val attached = WeakHashMap<SurfaceView, Unit>()

        fun attached(sv: SurfaceView) {
            attached[sv] = Unit
        }

        fun detached(sv: SurfaceView) {
            attached.remove(sv)
        }

        fun surfaces(): List<SurfaceView> = attached.keys.toList()
    }

    /** Accès par réflexion aux méthodes cachées du compositeur. */
    private object Hidden {
        private val setEffectLayer: Method? = find(SurfaceControl.Builder::class.java, "setEffectLayer")
        private val setBlur: Method? = find(
            SurfaceControl.Transaction::class.java, "setBackgroundBlurRadius",
            SurfaceControl::class.java, Int::class.javaPrimitiveType!!,
        )
        private val setCorner: Method? = find(
            SurfaceControl.Transaction::class.java, "setCornerRadius",
            SurfaceControl::class.java, Float::class.javaPrimitiveType!!,
        )

        val available = (setEffectLayer != null && setBlur != null && setCorner != null).also {
            ReelsLog.i("flou vidéo: ${if (it) "disponible" else "NON disponible (méthode cachée manquante)"}")
        }

        fun createEffectLayer(parent: SurfaceControl): SurfaceControl {
            val builder = SurfaceControl.Builder().setName("instamod-reels-video-blur").setParent(parent)
            setEffectLayer!!.invoke(builder)
            return builder.build()
        }

        fun setBackgroundBlurRadius(t: SurfaceControl.Transaction, sc: SurfaceControl, radius: Int) {
            setBlur!!.invoke(t, sc, radius)
        }

        fun setCornerRadius(t: SurfaceControl.Transaction, sc: SurfaceControl, radius: Float) {
            setCorner!!.invoke(t, sc, radius)
        }

        private fun find(cls: Class<*>, name: String, vararg params: Class<*>): Method? = try {
            cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (t: Throwable) {
            ReelsLog.w("méthode cachée $name non accessible: ${t.javaClass.simpleName}")
            null
        }
    }
}
