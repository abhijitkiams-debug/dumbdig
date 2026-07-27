package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A weapon projectile in flight under gravity and wind. Each astra is a "living
 * weapon" with its own silhouette and motion language: the arrow flies clean,
 * Agneyastra blazes as a flickering comet, Nagastra weaves as a serpent,
 * Kaumodaki spins as a mace, and the Brahmastra pulses with rotating divine
 * rays. All drawn from primitives, animated by an internal clock.
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
    var age = 0
    private val trail = ArrayList<Float>()   // flattened x,y pairs
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    fun update() {
        age++
        trail.add(x); trail.add(y)
        if (trail.size > 48) { trail.removeAt(0); trail.removeAt(0) }
        vy += gravity * weapon.gravityScale
        vx += wind
        x += vx; y += vy
    }

    fun draw(canvas: Canvas, unit: Float) {
        drawTrail(canvas, unit)
        val ang = atan2(vy, vx)
        when (weapon) {
            Weapon.BAAN -> drawArrow(canvas, unit, ang)
            Weapon.AGNI -> drawAgni(canvas, unit)
            Weapon.NAGA -> drawNaga(canvas, unit, ang)
            Weapon.GADA -> drawGada(canvas, unit)
            Weapon.BRAHMA -> drawBrahma(canvas, unit)
        }
    }

    private fun pulse(mult: Float = 0.15f) = 1f + mult * sin(age * 0.4f)

    private fun drawTrail(canvas: Canvas, unit: Float) {
        var i = 0; val n = (trail.size / 2).coerceAtLeast(1); var k = 0
        val tint = when (weapon) { Weapon.AGNI -> 0xFFFF9A3A.toInt(); Weapon.BRAHMA -> 0xFFFFF0B0.toInt(); else -> weapon.color }
        while (i < trail.size - 1) {
            val a = (130 * (k + 1) / n).coerceIn(0, 170)
            paint.color = (a shl 24) or (tint and 0x00FFFFFF)
            canvas.drawCircle(trail[i], trail[i + 1], unit * (0.22f + 0.4f * k / n), paint)
            i += 2; k++
        }
    }

    private fun outerGlow(canvas: Canvas, unit: Float, r: Float, color: Int) {
        paint.color = (0x44 shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(x, y, r * 2.0f * pulse(0.2f), paint)
    }

    private fun drawArrow(canvas: Canvas, unit: Float, ang: Float) {
        val dx = cos(ang); val dy = sin(ang)
        val px = -dy; val py = dx
        val len = unit * 2.6f
        val tipX = x + dx * len; val tipY = y + dy * len
        val tailX = x - dx * len; val tailY = y - dy * len
        stroke.color = 0xFF6A4A2A.toInt(); stroke.strokeWidth = unit * 0.45f
        canvas.drawLine(tailX, tailY, tipX, tipY, stroke)
        paint.color = weapon.color
        path.reset()
        path.moveTo(tipX + dx * unit * 0.9f, tipY + dy * unit * 0.9f)
        path.lineTo(x + dx * len * 0.55f + px * unit * 0.6f, y + dy * len * 0.55f + py * unit * 0.6f)
        path.lineTo(x + dx * len * 0.55f - px * unit * 0.6f, y + dy * len * 0.55f - py * unit * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = 0xFFD03A3A.toInt()
        for (s in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(tailX, tailY)
            path.lineTo(tailX - dx * unit * 1.1f + px * unit * 0.7f * s, tailY - dy * unit * 1.1f + py * unit * 0.7f * s)
            path.lineTo(tailX - dx * unit * 0.4f, tailY - dy * unit * 0.4f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawAgni(canvas: Canvas, unit: Float) {
        outerGlow(canvas, unit, unit * 1.4f, 0xFFFF6A1E.toInt())
        // Flickering flame: layered circles with jittered radii, hottest at core.
        val flick = 0.8f + Math.random().toFloat() * 0.5f
        paint.color = 0xCCFF4D1E.toInt(); canvas.drawCircle(x, y, unit * 1.5f * flick, paint)
        paint.color = 0xEEFF9A2E.toInt(); canvas.drawCircle(x, y, unit * 1.0f * flick, paint)
        paint.color = 0xFFFFE07A.toInt(); canvas.drawCircle(x, y, unit * 0.55f, paint)
        // Rising ember tongues.
        paint.color = 0xAAFFC23A.toInt()
        for (s in intArrayOf(-1, 0, 1)) {
            val ex = x + s * unit * 0.6f + (Math.random().toFloat() - 0.5f) * unit
            canvas.drawCircle(ex, y - unit * (1.2f + Math.random().toFloat()), unit * 0.35f, paint)
        }
    }

    private fun drawNaga(canvas: Canvas, unit: Float, ang: Float) {
        outerGlow(canvas, unit, unit * 1.1f, 0xFF49E07A.toInt())
        val dx = cos(ang); val dy = sin(ang); val px = -dy; val py = dx
        // A weaving serpent body: segments offset perpendicular by a travelling sine.
        paint.color = 0xFF2FA85A.toInt()
        for (seg in 0..5) {
            val back = seg * unit * 0.85f
            val wob = sin(age * 0.5f - seg * 0.9f) * unit * 0.9f
            val sx = x - dx * back + px * wob
            val sy = y - dy * back + py * wob
            canvas.drawCircle(sx, sy, unit * (0.7f - seg * 0.07f), paint)
        }
        // Head.
        paint.color = 0xFF7CF0A6.toInt(); canvas.drawCircle(x, y, unit * 0.8f, paint)
        paint.color = Color.RED
        canvas.drawCircle(x + px * unit * 0.25f, y + py * unit * 0.25f, unit * 0.16f, paint)
        canvas.drawCircle(x - px * unit * 0.25f, y - py * unit * 0.25f, unit * 0.16f, paint)
    }

    private fun drawGada(canvas: Canvas, unit: Float) {
        outerGlow(canvas, unit, unit * 1.2f, 0xFFCBA36A.toInt())
        // Spinning spiked mace head.
        paint.color = 0xFFB07A3A.toInt(); canvas.drawCircle(x, y, unit * 1.2f, paint)
        paint.color = 0xFF7A5326.toInt()
        val rot = age * 0.5f
        for (a in 0 until 8) {
            val an = rot + a * (Math.PI.toFloat() / 4f)
            canvas.drawCircle(x + cos(an) * unit * 1.5f, y + sin(an) * unit * 1.5f, unit * 0.35f, paint)
        }
        paint.color = 0xFFE8C88A.toInt(); canvas.drawCircle(x, y, unit * 0.5f, paint)
    }

    private fun drawBrahma(canvas: Canvas, unit: Float) {
        // Grand pulsing divine orb with rotating rays — a moving sun.
        paint.color = 0x55FFE14A.toInt(); canvas.drawCircle(x, y, unit * 3.2f * pulse(0.25f), paint)
        stroke.color = 0xCCFFF0B0.toInt(); stroke.strokeWidth = unit * 0.4f
        val rot = age * 0.28f
        for (a in 0 until 8) {
            val an = rot + a * (Math.PI.toFloat() / 4f)
            val r1 = unit * 1.4f; val r2 = unit * (2.6f + 0.6f * sin(age * 0.4f + a))
            canvas.drawLine(x + cos(an) * r1, y + sin(an) * r1, x + cos(an) * r2, y + sin(an) * r2, stroke)
        }
        paint.color = 0xFFFFE14A.toInt(); canvas.drawCircle(x, y, unit * 1.5f * pulse(), paint)
        paint.color = Color.WHITE; canvas.drawCircle(x, y, unit * 0.8f, paint)
    }
}
