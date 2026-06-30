package com.originalgames.flipside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * A spike hazard anchored to either the floor or the ceiling. The player must
 * be on the opposite surface (or mid-flip) to pass safely.
 */
class Obstacle(
    var x: Float,
    val width: Float,
    val height: Float,
    val onFloor: Boolean,
    private val floorY: Float,
    private val ceilingY: Float
) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 60
    }

    fun update(speed: Float) {
        x -= speed
    }

    fun isOffScreen() = x + width < 0f

    fun draw(canvas: Canvas, color: Int) {
        path.reset()
        if (onFloor) {
            path.moveTo(x, floorY)
            path.lineTo(x + width / 2f, floorY - height)
            path.lineTo(x + width, floorY)
        } else {
            path.moveTo(x, ceilingY)
            path.lineTo(x + width / 2f, ceilingY + height)
            path.lineTo(x + width, ceilingY)
        }
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
        edgePaint.strokeWidth = width * 0.06f
        canvas.drawPath(path, edgePaint)
    }

    /**
     * Triangle-vs-circle is approximated with a tightened bounding box around
     * the spike body. Slightly forgiving on purpose so near-misses feel good.
     */
    fun collidesWith(p: RectF): Boolean {
        val inset = width * 0.18f
        val left = x + inset
        val right = x + width - inset
        if (p.right < left || p.left > right) return false
        return if (onFloor) {
            p.bottom > floorY - height * 0.78f
        } else {
            p.top < ceilingY + height * 0.78f
        }
    }
}
