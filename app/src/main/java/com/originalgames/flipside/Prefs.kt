package com.originalgames.flipside

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences for the only thing worth persisting:
 * the player's best score. Kept deliberately small and dependency-free.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var bestScore: Int
        get() = sp.getInt(KEY_BEST, 0)
        set(value) = sp.edit().putInt(KEY_BEST, value).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    /** Returns true if [score] is a new record (and stores it). */
    fun submitScore(score: Int): Boolean {
        if (score > bestScore) {
            bestScore = score
            return true
        }
        return false
    }

    companion object {
        private const val FILE = "flipside_prefs"
        private const val KEY_BEST = "best_score"
        private const val KEY_SOUND = "sound_enabled"
    }
}
