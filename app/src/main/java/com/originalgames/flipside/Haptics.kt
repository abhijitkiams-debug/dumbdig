package com.originalgames.flipside

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Thin, crash-proof wrapper around the device vibrator for tactile feedback.
 * Pulses are deliberately tiny so flips and pickups feel "clicky" rather than
 * buzzy. Honours an enabled flag so it can be toggled with the rest of the FX.
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

    /** A crisp tick for flipping gravity. */
    fun light() = buzz(12L)

    /** A slightly fuller pop for collecting a gem. */
    fun pop() = buzz(18L)

    /** A heavier thud for crashing. */
    fun crash() = buzz(45L)

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
