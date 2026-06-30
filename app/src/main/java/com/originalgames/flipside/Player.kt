package com.originalgames.flipside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs

/**
 * The player avatar: a glowing orb fixed horizontally while the world scrolls
 * past. Tapping flips the direction of gravity, so the orb falls toward either
 * the floor or the ceiling. A short rotation/trail is tracked purely for feel.
 */
class Player(
    val x: Float,
    var y: Float,
    val radius: Float,
    private val floorY: Float,
    private val ceilingY: Float
) {
    // +1 = gravity pulls down, -1 = gravity pulls up.
    var gravityDir = 1
        private set

    var velY = 0f
    var rotation = 0f

    private val gravity = radius * 0.16f   // tuned relative to size for any screen
    private val maxFall = radius * 1.1f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Recent positions for a fading motion trail. */
    private val trail = ArrayDeque<Float>() // stores y values
    private val trailMax = 7

    fun flip() {
        gravityDir = -gravityDir
        // A little kick away from the surface makes flips feel responsive.
        velY = gravityDir * gravity * 6f
    }

    fun reset() {
        gravityDir = 1
        velY = 0f
        rotation = 0f
        y = floorY - radius
        trail.clear()
    }

    fun update() {
        velY += gravity * gravityDir
        velY = velY.coerceIn(-maxFall, maxFall)
        y += velY

        val top = ceilingY + radius
        val bottom = floorY - radius
        if (y < top) {
            y = top; velY = 0f
        } else if (y > bottom) {
            y = bottom; velY = 0f
        }

        rotation += abs(velY) * 0.4f + 4f

        trail.addLast(y)
        if (trail.size > trailMax) trail.removeFirst()
    }

    fun draw(canvas: Canvas, hueColor: Int) {
        // Motion trail.
        var i = 0
        val n = trail.size
        for (ty in trail) {
            val alpha = (40 * (i + 1) / n)
            glowPaint.color = (alpha shl 24) or (hueColor and 0x00FFFFFF)
            val r = radius * (0.55f + 0.45f * i / n)
            canvas.drawCircle(x, ty, r, glowPaint)
            i++
        }

        // Outer glow.
        glowPaint.color = (0x55 shl 24) or (hueColor and 0x00FFFFFF)
        canvas.drawCircle(x, y, radius * 1.6f, glowPaint)

        // Body.
        bodyPaint.color = hueColor
        canvas.drawCircle(x, y, radius, bodyPaint)

        // A small offset core that points toward gravity for a sense of spin.
        val coreOffset = radius * 0.35f * gravityDir
        canvas.drawCircle(x, y + coreOffset, radius * 0.32f, corePaint)
    }

    /** Axis-aligned bounds, slightly inset so collisions feel fair. */
    fun bounds(out: RectF) {
        val r = radius * 0.82f
        out.set(x - r, y - r, x + r, y + r)
    }
}
