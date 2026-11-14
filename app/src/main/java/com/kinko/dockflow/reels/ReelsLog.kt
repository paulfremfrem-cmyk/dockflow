package com.kinko.dockflow.reels

import de.robv.android.xposed.XposedBridge

/**
 * Journalisation + isolation des pannes, comme igglass: une partie qui échoue
 * est désactivée et journalisée, Instagram ne plante jamais à cause du module.
 */
internal object ReelsLog {

    private const val TAG = "[InstaMod/Reels] "

    fun i(msg: String) {
        XposedBridge.log(TAG + msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        val detail = t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        XposedBridge.log(TAG + msg + detail)
    }

    /** Exécute [block]; en cas d'échec journalise et renvoie null. */
    inline fun <T> safe(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        w("échec: $what (partie désactivée)", t)
        null
    }
}
