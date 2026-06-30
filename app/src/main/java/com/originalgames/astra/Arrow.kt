package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.atan2

/**
 * An arrow (baan) loosed by Ram. Flies upward toward Ravan. Carries its own
 * damage and a couple of run-modifying flags granted by boons: [pierce] lets it
 * pass through targets, and a non-null [homing] target nudges it toward an
 * aim point each frame (used by the Nagastra serpent volley).
 */
class Arrow(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val damage: Float,
    val length: Float,
    val color: Int,
    val pierce: Boolean = false,
    val homing: Boolean = false
) {
    var alive = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** Steer toward [tx],[ty] when homing; otherwise fly straight. */
    fun update(tx: Float, ty: Float) {
        if (homing && (tx != x || ty != y)) {
            val dx = tx - x; val dy = ty - y
            val d = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val speed = kotlin.math.hypot(vx, vy)
            vx += (dx / d) * speed * 0.18f
            vy += (dy / d) * speed * 0.18f
            val s2 = kotlin.math.hypot(vx, vy).coerceAtLeast(1f)
            vx = vx / s2 * speed
            vy = vy / s2 * speed
        }
        x += vx; y += vy
    }

    fun isOffScreen(h: Float) = y < -length || y > h + length || x < -length || x > 4000f

    fun bounds(out: RectF, pad: Float = 0f) {
        out.set(x - pad, y - length, x + pad, y + length)
    }

    fun draw(canvas: Canvas) {
        val ang = atan2(vy, vx)
        val tipX = x; val tipY = y
        val tailX = x - kotlin.math.cos(ang).toFloat() * length
        val tailY = y - kotlin.math.sin(ang).toFloat() * length
        paint.color = (0x55 shl 24) or (color and 0x00FFFFFF)
        paint.strokeWidth = length * 0.42f
        canvas.drawLine(tailX, tailY, tipX, tipY, paint)
        paint.color = color
        paint.strokeWidth = length * 0.2f
        canvas.drawLine(tailX, tailY, tipX, tipY, paint)
    }
}
