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

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(value) = sp.edit().putBoolean(KEY_HAPTICS, value).apply()

    // --- Daily challenge streak ---

    var dailyStreak: Int
        get() = sp.getInt(KEY_STREAK, 0)
        private set(value) = sp.edit().putInt(KEY_STREAK, value).apply()

    private var lastDailyDate: String?
        get() = sp.getString(KEY_LAST_DAILY, null)
        set(value) = sp.edit().putString(KEY_LAST_DAILY, value).apply()

    /**
     * Records that the player finished today's daily run and returns the updated
     * streak. The streak grows when consecutive calendar days are played and
     * resets to 1 after a gap; replaying the same day leaves it unchanged.
     */
    fun registerDailyCompletion(today: String, yesterday: String): Int {
        val last = lastDailyDate
        when (last) {
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

    // --- Daily ghost (best run of the day, replayed as a racing shadow) ---
    // Stored as "survivedTicks;flickTick1,flickTick2,..." keyed to a date.

    fun ghostFor(date: String): String? =
        if (sp.getString(KEY_GHOST_DATE, null) == date) sp.getString(KEY_GHOST_DATA, null) else null

    fun ghostScoreFor(date: String): Int =
        if (sp.getString(KEY_GHOST_DATE, null) == date) sp.getInt(KEY_GHOST_SCORE, 0) else 0

    /** Saves [data] as today's ghost if it beats the stored score (or the day rolled over). */
    fun saveGhostIfBetter(date: String, score: Int, data: String): Boolean {
        val sameDay = sp.getString(KEY_GHOST_DATE, null) == date
        if (sameDay && score <= sp.getInt(KEY_GHOST_SCORE, 0)) return false
        sp.edit()
            .putString(KEY_GHOST_DATE, date)
            .putInt(KEY_GHOST_SCORE, score)
            .putString(KEY_GHOST_DATA, data)
            .apply()
        return true
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
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_STREAK = "daily_streak"
        private const val KEY_LAST_DAILY = "last_daily_date"
        private const val KEY_GHOST_DATE = "ghost_date"
        private const val KEY_GHOST_SCORE = "ghost_score"
        private const val KEY_GHOST_DATA = "ghost_data"
    }
}
