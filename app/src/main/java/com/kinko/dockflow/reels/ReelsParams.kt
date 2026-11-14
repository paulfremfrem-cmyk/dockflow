package com.kinko.dockflow.reels

/**
 * Paramètres du rendu Reels, repris tels quels de igglass (maciasdav/igglass, MIT).
 *
 * Ce package ne touche PAS à notre CustomNavBarView: il ne fait que reproduire
 * la façon dont igglass gère l'écran Reels (vidéo plein écran + UI au-dessus de
 * la capsule + flou compositeur sur la vidéo).
 */
internal object ReelsParams {

    /** Tag de notre barre flottante, seule chose qu'on lit sur elle. */
    const val BAR_TAG = "instamod_custom_floating_bar"

    /** Active tout le traitement Reels. */
    const val REELS_ENABLED = true

    /**
     * Keep Instagram's original Reels UI placement. Footer positioning is handled
     * by the earlier custom-slider tracking logic in FloatingNavHook; the video fill,
     * gradient stretch and compositor blur remain independent of this setting.
     */
    const val LIFT_UI = false

    /**
     * Reels plus bas que la page (écrans 20:9) sont agrandis depuis le centre
     * jusqu'à remplir la page, en recadrant les côtés, au lieu de laisser des
     * bandes noires. Agrandissement fait par layout (pas par scaleX/Y) parce que
     * la surface vidéo est peinte par le compositeur à la taille de la vue.
     */
    const val FILL_VIDEO = true

    /** Échelle maximale pour remplir: un reel 9:16 sur 20:9 demande ~1.2. */
    const val FILL_MAX_SCALE = 1.3f

    /** Espace entre l'interface remontée du reel et la capsule. */
    const val UI_GAP_DP = 4f

    /**
     * Flou du compositeur sur les vidéos (SurfaceView), que la capture normale
     * ne peut pas voir. 0 = désactivé: le vidéo reste net sous la capsule.
     */
    const val VIDEO_BLUR_RADIUS_DP = 16f

    /**
     * Optional exclusions used only when LIFT_UI is enabled. The active Reels
     * placement keeps this disabled and lets FloatingNavHook position the custom
     * slider and footer together, matching the earlier working behavior.
     */
    val SKIP_LIFT_IDS = emptySet<String>()

    // Ancres Instagram, par nom de ressource (jamais par ID numérique).
    const val ID_REELS_PAGER = "clips_viewer_view_pager"
    const val ID_VIDEO_CONTAINER = "clips_video_container"
    const val ID_GRADIENT = "clips_bottom_legibility_gradient_component"
    const val ID_UFI = "clips_ufi_component"
    const val ID_SCRUBBER = "scrubber"
    const val ID_TAB_BAR = "tab_bar"
}
