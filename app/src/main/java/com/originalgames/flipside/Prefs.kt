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

    /** Total gems ever collected — the currency that unlocks orb skins. */
    var lifetimeGems: Int
        get() = sp.getInt(KEY_LIFETIME_GEMS, 0)
        set(value) = sp.edit().putInt(KEY_LIFETIME_GEMS, value).apply()

    /** Index into [Skins.all] of the orb skin the player has equipped. */
    var selectedSkin: Int
        get() = sp.getInt(KEY_SKIN, 0)
        set(value) = sp.edit().putInt(KEY_SKIN, value).apply()

    fun addLifetimeGems(n: Int) {
        if (n > 0) lifetimeGems += n
    }

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
        private const val KEY_LIFETIME_GEMS = "lifetime_gems"
        private const val KEY_SKIN = "selected_skin"
    }
}
