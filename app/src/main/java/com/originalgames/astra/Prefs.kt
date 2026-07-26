package com.originalgames.astra

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences for the handful of things worth
 * persisting: best score, the most heads ever severed in a run (the bragging
 * metric), FX toggles, and the daily-challenge streak. Kept deliberately small
 * and dependency-free.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var bestScore: Int
        get() = sp.getInt(KEY_BEST, 0)
        set(value) = sp.edit().putInt(KEY_BEST, value).apply()

    /** The most of Ravan's ten heads ever severed in a single run. */
    var mostHeads: Int
        get() = sp.getInt(KEY_HEADS, 0)
        set(value) = sp.edit().putInt(KEY_HEADS, value).apply()

    /** Total duels won against Ravan. */
    var wins: Int
        get() = sp.getInt(KEY_WINS, 0)
        set(value) = sp.edit().putInt(KEY_WINS, value).apply()

    // Unlockable powers, bought with wins, then equipped for battle.
    var armourOwned: Boolean
        get() = sp.getBoolean(KEY_ARM_OWN, false)
        set(v) = sp.edit().putBoolean(KEY_ARM_OWN, v).apply()
    var rathOwned: Boolean
        get() = sp.getBoolean(KEY_RATH_OWN, false)
        set(v) = sp.edit().putBoolean(KEY_RATH_OWN, v).apply()
    var armourOn: Boolean
        get() = sp.getBoolean(KEY_ARM_ON, false)
        set(v) = sp.edit().putBoolean(KEY_ARM_ON, v).apply()
    var rathOn: Boolean
        get() = sp.getBoolean(KEY_RATH_ON, false)
        set(v) = sp.edit().putBoolean(KEY_RATH_ON, v).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(value) = sp.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Returns true if [score] is a new record (and stores it). */
    fun submitScore(score: Int): Boolean {
        if (score > bestScore) {
            bestScore = score
            return true
        }
        return false
    }

    /** Records the heads severed this run, keeping the lifetime maximum. */
    fun submitHeads(heads: Int) {
        if (heads > mostHeads) mostHeads = heads
    }

    // --- Daily challenge streak ---

    var dailyStreak: Int
        get() = sp.getInt(KEY_STREAK, 0)
        private set(value) = sp.edit().putInt(KEY_STREAK, value).apply()

    private var lastDailyDate: String?
        get() = sp.getString(KEY_LAST_DAILY, null)
        set(value) = sp.edit().putString(KEY_LAST_DAILY, value).apply()

    /**
     * Records that the player finished today's daily battle and returns the
     * updated streak. The streak grows when consecutive calendar days are
     * played and resets to 1 after a gap; replaying the same day leaves it
     * unchanged.
     */
    fun registerDailyCompletion(today: String, yesterday: String): Int {
        when (lastDailyDate) {
            today -> { /* already counted today */ }
            yesterday -> dailyStreak += 1
            else -> dailyStreak = 1
        }
        lastDailyDate = today
        return dailyStreak
    }

    /** Returns the live streak value, expiring it if the player skipped a day. */
    fun currentStreak(today: String, yesterday: String): Int {
        val last = lastDailyDate ?: return 0
        return if (last == today || last == yesterday) dailyStreak else 0
    }

    companion object {
        private const val FILE = "astra_prefs"
        private const val KEY_BEST = "best_score"
        private const val KEY_HEADS = "most_heads"
        private const val KEY_WINS = "wins"
        private const val KEY_ARM_OWN = "armour_owned"
        private const val KEY_RATH_OWN = "rath_owned"
        private const val KEY_ARM_ON = "armour_on"
        private const val KEY_RATH_ON = "rath_on"
        const val ARMOUR_COST = 2
        const val RATH_COST = 5
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_STREAK = "daily_streak"
        private const val KEY_LAST_DAILY = "last_daily_date"
    }
}
