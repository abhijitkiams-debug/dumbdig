package com.originalgames.dhishoom

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Thin, crash-proof wrapper around the device vibrator for fight feedback.
 * Light tick for a whiff/menu, a fuller pop for a clean hit, a heavier thud for
 * a knockdown / K.O. Honours an enabled flag so it toggles with the FX switch.
 */
class Haptics(context: Context, var enabled: Boolean = true) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    private val available = vibrator?.hasVibrator() == true

    /** A crisp tick for menu taps and blocked hits. */
    fun light() = buzz(12L)

    /** A fuller pop for landing a clean strike. */
    fun hit() = buzz(22L)

    /** A heavier thud for a knockdown or K.O. */
    fun heavy() = buzz(55L)

    private fun buzz(ms: Long) {
        if (!enabled || !available) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Throwable) {
            // Haptics are non-essential; never let them affect gameplay.
        }
    }
}
