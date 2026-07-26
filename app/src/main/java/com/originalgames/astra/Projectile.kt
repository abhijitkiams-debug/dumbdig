package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A weapon projectile in flight under gravity and wind — the heart of the
 * artillery arc. Light astras (the arrows) render as an actual shaft + head +
 * fletching oriented along flight; heavy astras render as a glowing energy orb.
 * A fading trail keeps the shot readable.
 */
class Projectile(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val weapon: Weapon,
    private val gravity: Float,
    private val wind: Float
) {
    var alive = true
    private val trail = ArrayList<Float>()   // flattened x,y pairs
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    private val isArrow get() = weapon == Weapon.BAAN || weapon == Weapon.NAGA

    fun update() {
        trail.add(x); trail.add(y)
        if (trail.size > 40) { trail.removeAt(0); trail.removeAt(0) }
        vy += gravity * weapon.gravityScale
        vx += wind
        x += vx; y += vy
    }

    fun draw(canvas: Canvas, unit: Float) {
        drawTrail(canvas, unit)
        val ang = atan2(vy, vx)
        if (isArrow) drawArrow(canvas, unit, ang) else drawOrb(canvas, unit)
    }

    private fun drawTrail(canvas: Canvas, unit: Float) {
        var i = 0; val n = (trail.size / 2).coerceAtLeast(1); var k = 0
        while (i < trail.size - 1) {
            val a = (110 * (k + 1) / n).coerceIn(0, 150)
            paint.color = (a shl 24) or (weapon.color and 0x00FFFFFF)
            canvas.drawCircle(trail[i], trail[i + 1], unit * 0.28f, paint)
            i += 2; k++
        }
    }

    private fun drawArrow(canvas: Canvas, unit: Float, ang: Float) {
        val dx = cos(ang).toFloat(); val dy = sin(ang).toFloat()
        val px = -dy; val py = dx
        val len = unit * 2.6f
        val tipX = x + dx * len; val tipY = y + dy * len
        val tailX = x - dx * len; val tailY = y - dy * len
        // Shaft.
        stroke.color = 0xFF6A4A2A.toInt(); stroke.strokeWidth = unit * 0.45f
        canvas.drawLine(tailX, tailY, tipX, tipY, stroke)
        // Metal arrowhead.
        paint.color = weapon.color
        path.reset()
        path.moveTo(tipX + dx * unit * 0.9f, tipY + dy * unit * 0.9f)
        path.lineTo(x + dx * len * 0.55f + px * unit * 0.6f, y + dy * len * 0.55f + py * unit * 0.6f)
        path.lineTo(x + dx * len * 0.55f - px * unit * 0.6f, y + dy * len * 0.55f - py * unit * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
        // Fletching (feathers) at the tail.
        paint.color = if (weapon == Weapon.NAGA) 0xFF2FA85A.toInt() else 0xFFD03A3A.toInt()
        for (s in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(tailX, tailY)
            path.lineTo(tailX - dx * unit * 1.1f + px * unit * 0.7f * s, tailY - dy * unit * 1.1f + py * unit * 0.7f * s)
            path.lineTo(tailX - dx * unit * 0.4f, tailY - dy * unit * 0.4f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawOrb(canvas: Canvas, unit: Float) {
        val r = if (weapon == Weapon.BRAHMA) unit * 1.6f else unit * 1.1f
        paint.color = (0x55 shl 24) or (weapon.color and 0x00FFFFFF)
        canvas.drawCircle(x, y, r * 1.8f, paint)
        paint.color = weapon.color
        canvas.drawCircle(x, y, r, paint)
        paint.color = 0xFFFFF3C0.toInt()
        canvas.drawCircle(x, y, r * 0.45f, paint)
    }
}
