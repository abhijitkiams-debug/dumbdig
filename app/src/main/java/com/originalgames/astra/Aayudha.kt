package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.sin

/**
 * An incoming weapon (aayudha) hurled down by Ravan. A single class covers the
 * whole arsenal via [type]; each type has its own motion and silhouette:
 *
 *  - BAAN    — a straight, fast arrow. Can be shot down by Ram's arrows.
 *  - CHAKRA  — a spinning discus that weaves side to side. Can be shot down.
 *  - GADA    — a heavy mace lobbed in an arc. Must be dodged.
 *  - TRISHUL — a trident that falls straight and fast. Must be dodged.
 *  - SHAKTI  — a homing fire-bolt that curves toward Ram. Must be dodged.
 *
 * Everything is drawn from primitives — no imported art.
 */
class Aayudha(
    val type: Type,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float
) {
    enum class Type { BAAN, CHAKRA, GADA, TRISHUL, SHAKTI, RBAAN }

    var alive = true
    private var spin = 0f
    private var t = 0f
    private val baseX = x
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Arrows can only cancel the lighter projectiles; heavy ones must be dodged.
     *  RBAAN is Ravan's own arrow — Ram's arrows clash with it mid-air. */
    val destructible: Boolean
        get() = type == Type.BAAN || type == Type.CHAKRA || type == Type.RBAAN

    fun update(targetX: Float, targetY: Float, gravityUnit: Float) {
        t += 1f
        spin += when (type) {
            Type.CHAKRA -> 22f
            Type.GADA -> 9f
            Type.TRISHUL -> 0f
            else -> 6f
        }
        when (type) {
            Type.GADA -> vy += gravityUnit * 0.6f // lobbed arc falls under gravity
            Type.SHAKTI -> {                      // curve toward Ram
                val dx = targetX - x; val dy = targetY - y
                val d = hypot(dx, dy).coerceAtLeast(1f)
                val sp = hypot(vx, vy)
                vx += (dx / d) * sp * 0.06f
                vy += (dy / d) * sp * 0.06f
                val s2 = hypot(vx, vy).coerceAtLeast(1f)
                vx = vx / s2 * sp; vy = vy / s2 * sp
            }
            Type.CHAKRA -> x = baseX + sin(t * 0.06f) * size * 3.2f
            else -> {}
        }
        x += vx; y += vy
    }

    fun isOffScreen(w: Float, h: Float) =
        y > h + size * 2f || x < -size * 3f || x > w + size * 3f

    fun hitRadius(): Float = size * when (type) {
        Type.GADA -> 1.0f
        Type.TRISHUL -> 0.8f
        else -> 0.85f
    }

    fun overlaps(cx: Float, cy: Float, r: Float): Boolean =
        hypot(cx - x, cy - y) < hitRadius() + r

    fun bounds(out: RectF) {
        val r = hitRadius()
        out.set(x - r, y - r, x + r, y + r)
    }

    fun draw(canvas: Canvas) {
        when (type) {
            Type.BAAN -> drawBaan(canvas)
            Type.CHAKRA -> drawChakra(canvas)
            Type.GADA -> drawGada(canvas)
            Type.TRISHUL -> drawTrishul(canvas)
            Type.SHAKTI -> drawShakti(canvas)
            Type.RBAAN -> drawRBaan(canvas)
        }
    }

    /** Ravan's aimed arrow: drawn pointing along its flight, in demon red. */
    private fun drawRBaan(canvas: Canvas) {
        glow(canvas, C_RBAAN, size * 1.3f)
        val ang = kotlin.math.atan2(vy, vx)
        val dx = kotlin.math.cos(ang).toFloat(); val dy = kotlin.math.sin(ang).toFloat()
        val tipX = x + dx * size * 1.7f; val tipY = y + dy * size * 1.7f
        val tailX = x - dx * size * 1.5f; val tailY = y - dy * size * 1.5f
        stroke.color = C_RBAAN
        stroke.strokeWidth = size * 0.34f
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(tailX, tailY, tipX, tipY, stroke)
        // Arrowhead.
        paint.color = C_RBAAN
        val px = -dy; val py = dx   // perpendicular
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(x + dx * size * 0.7f + px * size * 0.55f, y + dy * size * 0.7f + py * size * 0.55f)
        path.lineTo(x + dx * size * 0.7f - px * size * 0.55f, y + dy * size * 0.7f - py * size * 0.55f)
        path.close()
        canvas.drawPath(path, paint)
        // Fletching.
        paint.color = 0xFF6A1018.toInt()
        canvas.drawCircle(tailX, tailY, size * 0.3f, paint)
    }

    private fun glow(canvas: Canvas, color: Int, r: Float) {
        paint.color = (0x44 shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(x, y, r, paint)
    }

    private fun drawBaan(canvas: Canvas) {
        glow(canvas, C_BAAN, size * 1.2f)
        paint.color = C_BAAN
        stroke.color = C_BAAN
        stroke.strokeWidth = size * 0.35f
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, y - size * 1.4f, x, y + size * 1.4f, stroke)
        path.reset()
        path.moveTo(x, y + size * 1.7f)
        path.lineTo(x - size * 0.5f, y + size * 0.8f)
        path.lineTo(x + size * 0.5f, y + size * 0.8f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawChakra(canvas: Canvas) {
        glow(canvas, C_CHAKRA, size * 1.5f)
        stroke.color = C_CHAKRA
        stroke.strokeWidth = size * 0.28f
        canvas.drawCircle(x, y, size, stroke)
        paint.color = C_CHAKRA
        // Spokes that visibly spin.
        for (i in 0 until 8) {
            val a = Math.toRadians((spin + i * 45f).toDouble())
            val ex = x + kotlin.math.cos(a).toFloat() * size
            val ey = y + kotlin.math.sin(a).toFloat() * size
            canvas.drawCircle(ex, ey, size * 0.16f, paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, size * 0.22f, paint)
    }

    private fun drawGada(canvas: Canvas) {
        glow(canvas, C_GADA, size * 1.6f)
        // Handle.
        stroke.color = C_GADA
        stroke.strokeWidth = size * 0.3f
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, y, x, y + size * 1.7f, stroke)
        // Spiked head.
        paint.color = C_GADA
        canvas.drawCircle(x, y, size, paint)
        paint.color = 0xFF6A3D12.toInt()
        for (i in 0 until 8) {
            val a = Math.toRadians((spin + i * 45f).toDouble())
            val ex = x + kotlin.math.cos(a).toFloat() * size * 1.15f
            val ey = y + kotlin.math.sin(a).toFloat() * size * 1.15f
            canvas.drawCircle(ex, ey, size * 0.2f, paint)
        }
    }

    private fun drawTrishul(canvas: Canvas) {
        glow(canvas, C_TRISHUL, size * 1.4f)
        stroke.color = C_TRISHUL
        stroke.strokeWidth = size * 0.26f
        stroke.strokeCap = Paint.Cap.ROUND
        // Shaft.
        canvas.drawLine(x, y - size, x, y + size * 1.8f, stroke)
        paint.color = C_TRISHUL
        // Three prongs at the bottom (leading edge).
        for (dx in floatArrayOf(-1f, 0f, 1f)) {
            path.reset()
            val px = x + dx * size * 0.8f
            path.moveTo(px, y + size * 0.4f)
            path.lineTo(px - size * 0.28f, y + size * 1.2f)
            path.lineTo(px + size * 0.28f, y + size * 1.2f)
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.drawRect(x - size * 0.8f, y + size * 0.3f, x + size * 0.8f, y + size * 0.45f, paint)
    }

    private fun drawShakti(canvas: Canvas) {
        glow(canvas, C_SHAKTI, size * 1.8f)
        // A teardrop flame bolt pointing along its velocity.
        val ang = kotlin.math.atan2(vy, vx)
        val tipX = x + kotlin.math.cos(ang).toFloat() * size * 1.6f
        val tipY = y + kotlin.math.sin(ang).toFloat() * size * 1.6f
        paint.color = C_SHAKTI
        path.reset()
        path.moveTo(tipX, tipY)
        val left = ang + Math.PI / 2
        val right = ang - Math.PI / 2
        path.lineTo(x + kotlin.math.cos(left).toFloat() * size, y + kotlin.math.sin(left).toFloat() * size)
        path.lineTo(x - kotlin.math.cos(ang).toFloat() * size, y - kotlin.math.sin(ang).toFloat() * size)
        path.lineTo(x + kotlin.math.cos(right).toFloat() * size, y + kotlin.math.sin(right).toFloat() * size)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = 0xFFFFE08A.toInt()
        canvas.drawCircle(x, y, size * 0.4f, paint)
    }

    companion object {
        val C_BAAN = 0xFFE0E6EE.toInt()
        val C_CHAKRA = 0xFF34E0C8.toInt()
        val C_GADA = 0xFFB07A3A.toInt()
        val C_TRISHUL = 0xFFB0B7C4.toInt()
        val C_SHAKTI = 0xFFFF6A3D.toInt()
        val C_RBAAN = 0xFFFF4D5E.toInt()
    }
}
