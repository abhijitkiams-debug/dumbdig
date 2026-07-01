package com.originalgames.dhishoom

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.random.Random

/**
 * A lightweight burst particle pool used for hit sparks, blocks and K.O. debris.
 * Particles are plain structs updated in place; the system reuses dead slots so
 * it never allocates during steady-state play.
 */
class ParticleSystem(private val capacity: Int = 220) {

    private class P {
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var life = 0f; var maxLife = 1f
        var size = 0f
        var color = 0
        var gravity = 0.35f
        var alive = false
    }

    private val pool = Array(capacity) { P() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Omnidirectional burst (KO debris, etc). */
    fun burst(x: Float, y: Float, color: Int, count: Int, power: Float) {
        spawn(x, y, color, count, power, 0f, (2f * Math.PI).toFloat())
    }

    /**
     * A directional spark fan, e.g. a hit spraying away from the attacker.
     * [centerAngle] is the spray direction in radians, [spread] its half-width.
     */
    fun spark(x: Float, y: Float, color: Int, count: Int, power: Float, centerAngle: Float, spread: Float) {
        spawn(x, y, color, count, power, centerAngle, spread)
    }

    private fun spawn(x: Float, y: Float, color: Int, count: Int, power: Float, center: Float, spread: Float) {
        var spawned = 0
        for (p in pool) {
            if (spawned >= count) break
            if (p.alive) continue
            val angle = center + (Random.nextFloat() * 2f - 1f) * spread
            val speed = power * (0.3f + Random.nextFloat())
            p.x = x; p.y = y
            p.vx = kotlin.math.cos(angle) * speed
            p.vy = kotlin.math.sin(angle) * speed
            p.maxLife = 18f + Random.nextFloat() * 16f
            p.life = p.maxLife
            p.size = power * (0.10f + Random.nextFloat() * 0.16f)
            p.color = color
            p.gravity = 0.45f
            p.alive = true
            spawned++
        }
    }

    fun update() {
        for (p in pool) {
            if (!p.alive) continue
            p.x += p.vx
            p.y += p.vy
            p.vy += p.gravity
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
