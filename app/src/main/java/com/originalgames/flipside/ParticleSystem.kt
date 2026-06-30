package com.originalgames.flipside

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.random.Random

/**
 * A lightweight burst particle pool used for gem pickups and crashes. Particles
 * are plain structs updated in place; the system reuses dead slots so it never
 * allocates during steady-state play.
 */
class ParticleSystem(private val capacity: Int = 160) {

    private class P {
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var life = 0f; var maxLife = 1f
        var size = 0f
        var color = 0
        var alive = false
    }

    private val pool = Array(capacity) { P() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun burst(x: Float, y: Float, color: Int, count: Int, power: Float) {
        var spawned = 0
        for (p in pool) {
            if (spawned >= count) break
            if (p.alive) continue
            val angle = Random.nextFloat() * (2f * Math.PI.toFloat())
            val speed = power * (0.3f + Random.nextFloat())
            p.x = x; p.y = y
            p.vx = kotlin.math.cos(angle) * speed
            p.vy = kotlin.math.sin(angle) * speed
            p.maxLife = 22f + Random.nextFloat() * 18f
            p.life = p.maxLife
            p.size = power * (0.18f + Random.nextFloat() * 0.22f)
            p.color = color
            p.alive = true
            spawned++
        }
    }

    fun update() {
        for (p in pool) {
            if (!p.alive) continue
            p.x += p.vx
            p.y += p.vy
            p.vy += 0.35f          // gentle gravity on debris
            p.vx *= 0.98f
            p.life -= 1f
            if (p.life <= 0f) p.alive = false
        }
    }

    fun draw(canvas: Canvas) {
        for (p in pool) {
            if (!p.alive) continue
            val a = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            paint.color = (a shl 24) or (p.color and 0x00FFFFFF)
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
    }

    fun clear() {
        for (p in pool) p.alive = false
    }
}
