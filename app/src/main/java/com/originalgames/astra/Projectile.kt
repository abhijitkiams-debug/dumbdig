package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.atan2

/**
 * A weapon projectile in flight, under gravity and wind — the heart of the
 * artillery arc. Leaves a fading dotted trail so the shot reads clearly.
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

    fun update() {
        trail.add(x); trail.add(y)
        if (trail.size > 40) { trail.removeAt(0); trail.removeAt(0) }
        vy += gravity * weapon.gravityScale
        vx += wind
        x += vx; y += vy
    }

    fun draw(canvas: Canvas, unit: Float) {
        // Trail.
        var i = 0
        val n = trail.size / 2
        var k = 0
        while (i < trail.size - 1) {
            val a = (120 * (k + 1) / n).coerceIn(0, 160)
            paint.color = (a shl 24) or (weapon.color and 0x00FFFFFF)
            canvas.drawCircle(trail[i], trail[i + 1], unit * 0.35f, paint)
            i += 2; k++
        }
        // Head.
        val ang = atan2(vy, vx)
        paint.color = (0x66 shl 24) or (weapon.color and 0x00FFFFFF)
        canvas.drawCircle(x, y, unit * 1.4f, paint)
        paint.color = weapon.color
        canvas.drawCircle(x, y, unit * 0.7f, paint)
        // Fletching streak so it looks like an arrow/bolt.
        paint.color = Palette.WHITE
        val bx = x - kotlin.math.cos(ang).toFloat() * unit * 1.2f
        val by = y - kotlin.math.sin(ang).toFloat() * unit * 1.2f
        canvas.drawCircle(bx, by, unit * 0.3f, paint)
    }
}
