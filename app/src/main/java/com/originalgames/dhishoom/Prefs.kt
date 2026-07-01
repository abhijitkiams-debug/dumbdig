package com.originalgames.dhishoom

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences for the handful of things worth
 * persisting between sessions: lifetime match record, the best combo the player
 * has ever landed, the equipped fighter, and the FX toggle.
 *
 * Deliberately small and dependency-free.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Career wins (against the bot, counts toward fighter unlocks). */
    var careerWins: Int
        get() = sp.getInt(KEY_WINS, 0)
        set(value) = sp.edit().putInt(KEY_WINS, value).apply()

    /** Career losses — shown on the title screen for a little stakes. */
    var careerLosses: Int
        get() = sp.getInt(KEY_LOSSES, 0)
        set(value) = sp.edit().putInt(KEY_LOSSES, value).apply()

    /** Longest combo ever landed, bragged about on the share card. */
    var bestCombo: Int
        get() = sp.getInt(KEY_BEST_COMBO, 0)
        set(value) = sp.edit().putInt(KEY_BEST_COMBO, value).apply()

    /** Index into [Roster.all] of the fighter the player has equipped. */
    var selectedFighter: Int
        get() = sp.getInt(KEY_FIGHTER, 0)
        set(value) = sp.edit().putInt(KEY_FIGHTER, value).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(value) = sp.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Records a finished bot match; returns true if this win was a new milestone. */
    fun recordResult(won: Boolean) {
        if (won) careerWins += 1 else careerLosses += 1
    }

    /** Stores [combo] if it beats the record; returns true when it does. */
    fun submitCombo(combo: Int): Boolean {
        if (combo > bestCombo) {
            bestCombo = combo
            return true
        }
        return false
    }

    companion object {
        private const val FILE = "dhishoom_prefs"
        private const val KEY_WINS = "career_wins"
        private const val KEY_LOSSES = "career_losses"
        private const val KEY_BEST_COMBO = "best_combo"
        private const val KEY_FIGHTER = "selected_fighter"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_HAPTICS = "haptics_enabled"
    }
}
