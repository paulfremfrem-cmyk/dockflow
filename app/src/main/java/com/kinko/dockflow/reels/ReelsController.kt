package com.kinko.dockflow.reels

import android.app.Activity
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Traitement de l'écran Reels, porté de igglass (maciasdav/igglass, MIT).
 *
 * Tout se cale sur `clips_viewer_view_pager` et sur le nom des ressources, jamais
 * sur des IDs numériques ni sur des classes ofusquées. Notre barre n'est lue
 * qu'en lecture seule: on prend son rectangle à l'écran pour savoir jusqu'où la
 * capsule couvre la vidéo.
 *
 * Reproduction fidèle de `TabBarGlass.liftReelsUi()`:
 *  - `clips_ufi_component` (boutons + légende) et `scrubber` remontent de
 *    `pagerBottom - barTop + 4dp` pour rester au-dessus de la capsule;
 *  - `clips_bottom_legibility_gradient_component` est étiré vers le haut au lieu
 *    de monter, sinon il flotterait au-dessus de la capsule;
 *  - `clips_video_container` est agrandi par layout jusqu'à remplir la page
 *    (max 1.3x), ce qui supprime les bandes noires des écrans 20:9.
 *
 * Le pager Reels n'existe pas au lancement (Instagram le gonfle à l'ouverture de
 * l'onglet). Comme igglass, on sonde donc l'arbre à chaque frame avec un cache:
 * on sonde le decorView de l'Activity, puis on ne fait plus rien tant que la
 * session Reels est vivante.
 */
internal object ReelsController {

    private const val IG_PACKAGE = "com.instagram.android"

    /** Délai minimal entre deux recherches du pager (5 Hz suffit largement). */
    private const val PROBE_INTERVAL_MS = 200L

    /** decorView déjà sondés. Faible: ne retient pas les Activities mortes. */
    private val probed = WeakHashMap<View, Unit>()

    /** Sessions vivantes, une par pager Reels. */
    private val sessions = WeakHashMap<View, Session>()

    private var lastProbe = 0L

    fun attach(activity: Activity) {
        if (!ReelsParams.REELS_ENABLED) return
        val decor = activity.window?.decorView ?: return
        if (probed.containsKey(decor)) return
        probed[decor] = Unit
        val listener = ViewTreeObserver.OnPreDrawListener {
            ReelsLog.safe("sonde Reels") { probe(decor) }
            true
        }
        decor.viewTreeObserver.addOnPreDrawListener(listener)
        ReelsLog.i("sonde Reels installée (${activity.javaClass.simpleName})")
    }

    /** Cherche le pager Reels sans bloquer; crée la session dès qu'il est mesuré. */
    private fun probe(decor: View) {
        if (sessions.isNotEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastProbe < PROBE_INTERVAL_MS) return
        lastProbe = now

        val pager = findPager(decor) ?: return
        if (!pager.isAttachedToWindow || pager.width == 0 || pager.height == 0) return
        if (sessions.containsKey(pager)) return

        val session = Session(pager) { sessions.remove(pager) }
        sessions[pager] = session
        session.install()
    }

    private fun findPager(decor: View): View? {
        val id = decor.resources.getIdentifier(ReelsParams.ID_REELS_PAGER, "id", IG_PACKAGE)
        if (id == 0) return null
        return decor.findViewById(id)
    }

    private fun idOf(view: View, name: String): Int =
        view.resources.getIdentifier(name, "id", IG_PACKAGE)

    /** Une session par pager Reels: suit le frame et applique le traitement. */
    private class Session(
        private val pager: View,
        private val onDetach: () -> Unit,
    ) {

        private val density = pager.resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        private val videoContainerId = idOf(pager, ReelsParams.ID_VIDEO_CONTAINER)
        private val gradientId = idOf(pager, ReelsParams.ID_GRADIENT)
        private val ufiId = idOf(pager, ReelsParams.ID_UFI)
        private val scrubberId = idOf(pager, ReelsParams.ID_SCRUBBER)

        /**
         * ID → nombre de parents à remonter avant de translater.
         * Les vues déjà pilotées par CustomReelsScrubberView sont exclues: deux
         * translations cumulées feraient sauter le footer du reel.
         */
        private val liftSpecs: Map<Int, Int> = buildMap {
            if (ufiId != 0 && ReelsParams.ID_UFI !in ReelsParams.SKIP_LIFT_IDS) put(ufiId, 1)
            if (scrubberId != 0 && ReelsParams.ID_SCRUBBER !in ReelsParams.SKIP_LIFT_IDS) put(scrubberId, 2)
        }

        /** Vidéos agrandies: hauteur appliquée → hauteur originale d'Instagram. */
        private val filledVideos = WeakHashMap<View, Pair<Int, Int>>()

        private val loc = IntArray(2)
        private var barRef: WeakReference<View>? = null
        private var videoBlur: ReelsVideoBlur? = null
        private var windowObserver: ViewTreeObserver? = null
        private var lastLift = Float.NaN
        private var lastMediaEffectsUpdate = 0L

        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            ReelsLog.safe("frame Reels") { onPreDraw() }
            true
        }

        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                ReelsLog.safe("démontage pager Reels") { uninstall() }
            }
        }

        fun install() {
            windowObserver = pager.viewTreeObserver
            pager.viewTreeObserver.addOnPreDrawListener(preDraw)
            pager.addOnAttachStateChangeListener(attachListener)
            ReelsLog.i(
                "session installée (pager ${pager.width}x${pager.height}, " +
                    "liftUi=${ReelsParams.LIFT_UI}, " +
                    "liftUfi=${ReelsParams.ID_UFI !in ReelsParams.SKIP_LIFT_IDS}, " +
                    "liftScrubber=${ReelsParams.ID_SCRUBBER !in ReelsParams.SKIP_LIFT_IDS}, " +
                    "fillVideo=${ReelsParams.FILL_VIDEO}, ids v=$videoContainerId g=$gradientId u=$ufiId s=$scrubberId)",
            )
        }

        private fun uninstall() {
            windowObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            pager.removeOnAttachStateChangeListener(attachListener)
            videoBlur?.release()
            videoBlur = null
            onDetach()
            ReelsLog.i("session démontée")
        }

        private fun onPreDraw() {
            if (!pager.isAttachedToWindow || pager.height <= 0) return
            if (!pager.isShown) return
            val bar = bar() ?: return

            if (videoBlur == null) {
                videoBlur = ReelsVideoBlur(bar) {
                    RectF(0f, 0f, bar.width.toFloat(), bar.height.toFloat())
                }
            }
            if (ReelsParams.LIFT_UI) liftReelsUi(bar) else applyMediaEffects(bar)
            videoBlur?.update()
        }

        /**
         * Reels: la vidéo descend jusqu'en bas, mais l'interface du reel est
         * ancrée au bas de la page et passerait sous la capsule. On la remonte
         * avec translationY (ce qui déplace aussi sa zone de touche) juste
         * au-dessus de la capsule. On parcourt toutes les pages chargées.
         */
        private fun liftReelsUi(bar: View) {
            pager.getLocationInWindow(loc)
            val pageBottom = loc[1] + pager.height
            bar.getLocationInWindow(loc)
            val barTop = loc[1]
            val lift = (pageBottom - barTop + dp(ReelsParams.UI_GAP_DP)).coerceAtLeast(0f)
            if (lift <= 0f) return

            val targets = ArrayList<View>()
            val videos = ArrayList<View>()
            val gradients = ArrayList<View>()
            collectReelsUi(pager, targets, videos, gradients)

            for (view in targets) {
                if (view.translationY != -lift) view.translationY = -lift
            }
            gradients.forEach { stretchGradient(it, lift) }
            if (ReelsParams.FILL_VIDEO) videos.forEach(::fillPage)

            if (lift != lastLift) {
                lastLift = lift
                ReelsLog.i(
                    "lift=${lift.roundToInt()}px (pageBottom=$pageBottom barTop=$barTop) " +
                        "ui=${targets.size} vidéos=${videos.size} dégradés=${gradients.size}",
                )
            }
        }

        /** Apply visual video treatments at most ~30 times per second while scrolling. */
        private fun applyMediaEffects(bar: View) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMediaEffectsUpdate < 32L) return
            lastMediaEffectsUpdate = now

            pager.getLocationInWindow(loc)
            val pageBottom = loc[1] + pager.height
            bar.getLocationInWindow(loc)
            val barTop = loc[1]
            val lift = (pageBottom - barTop + dp(ReelsParams.UI_GAP_DP)).coerceAtLeast(0f)
            if (lift <= 0f) return

            val targets = ArrayList<View>()
            val videos = ArrayList<View>()
            val gradients = ArrayList<View>()
            collectReelsUi(pager, targets, videos, gradients)
            gradients.forEach { stretchGradient(it, lift) }
            if (ReelsParams.FILL_VIDEO) videos.forEach(::fillPage)
        }

        /**
         * Instagram donne au conteneur vidéo la hauteur du vidéo à la largeur de
         * l'écran et le centre dans la page. S'il est un peu plus bas que la page,
         * on l'agrandit (hauteur de la page, largeur proportionnelle) et les côtés
         * sortent de l'écran. Ça doit passer par le layout: la surface vidéo est
         * peinte par le compositeur à la taille de la vue et ignore les scales.
         */
        private fun fillPage(video: View) {
            val page = video.parent as? View ?: return
            val lp = video.layoutParams ?: return
            if (page.width <= 0 || page.height <= 0) return
            // Hauteur posée par Instagram: l'actuelle, sauf si c'est la nôtre.
            val applied = filledVideos[video]
            val original = if (applied != null && lp.height == applied.first) applied.second else lp.height
            if (original <= 0) return
            val scale = page.height.toFloat() / original
            if (scale <= 1.001f || scale > ReelsParams.FILL_MAX_SCALE) return
            val width = (page.width * scale).roundToInt()
            if (lp.width != width || lp.height != page.height) {
                lp.width = width
                lp.height = page.height
                video.layoutParams = lp
                filledVideos[video] = page.height to original
            }
        }

        /**
         * Le dégradé qui assombrit le bas du vidéo pour rendre le texte lisible.
         * S'il montait avec le texte, il flotterait au-dessus de la capsule: on
         * le garde collé au bord inférieur et on l'allonge vers le haut de [lift] px.
         */
        private fun stretchGradient(view: View, lift: Float) {
            val h = view.height
            if (h <= 0) return
            val scale = (h + lift) / h
            if (view.translationY != 0f) view.translationY = 0f
            if (view.pivotY != h.toFloat()) view.pivotY = h.toFloat()
            if (view.scaleY != scale) view.scaleY = scale
        }

        /** Parcourt le pager Reels et rassemble interface à remonter, vidéos et dégradés. */
        private fun collectReelsUi(
            view: View,
            out: MutableList<View>,
            videos: MutableList<View>,
            gradients: MutableList<View>,
        ) {
            if (videoContainerId != 0 && view.id == videoContainerId) {
                videos += view
                return
            }
            if (gradientId != 0 && view.id == gradientId) {
                gradients += view
                return
            }
            val up = liftSpecs[view.id]
            if (up != null) {
                var target: View = view
                repeat(up) { (target.parent as? View)?.let { target = it } }
                if (target !in out) out += target
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectReelsUi(it, out, videos, gradients) }
            }
        }

        /** Notre barre flottante (lecture seule); repli sur `tab_bar`. */
        private fun bar(): View? {
            barRef?.get()?.let { if (it.isAttachedToWindow) return it }
            val root = pager.rootView ?: return null
            val found = root.findViewWithTag<View>(ReelsParams.BAR_TAG)
                ?: idOf(pager, ReelsParams.ID_TAB_BAR).takeIf { it != 0 }?.let { root.findViewById<View>(it) }
            barRef = found?.let { WeakReference(it) }
            return found
        }
    }
}
