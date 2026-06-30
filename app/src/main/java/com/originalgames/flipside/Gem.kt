package com.originalgames.flipside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot

/**
 * A collectible diamond worth bonus points. Spins gently and pulses so it
 * reads as "grab me" against the hazards.
 */
class Gem(
    var x: Float,
    val y: Float,
    val size: Float
) {
    var collected = false
    private var spin = 0f
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD25A") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFD25A.toInt() }

    fun update(speed: Float) {
        x -= speed
        spin += 5f
    }

    fun isOffScreen() = x + size < 0f

    fun draw(canvas: Canvas) {
        val pulse = 1f + 0.12f * kotlin.math.sin(Math.toRadians(spin.toDouble())).toFloat()
        val s = size * pulse
        canvas.drawCircle(x, y, s * 1.4f, glow)
        path.reset()
        path.moveTo(x, y - s)
        path.lineTo(x + s * 0.7f, y)
        path.lineTo(x, y + s)
        path.lineTo(x - s * 0.7f, y)
        path.close()
        canvas.drawPath(path, fill)
    }

    fun overlaps(p: RectF): Boolean {
        if (collected) return false
        val cx = p.centerX()
        val cy = p.centerY()
        return hypot(cx - x, cy - y) < (p.width() / 2f) + size
    }
}
