package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs

/**
 * The player avatar: Ram, the divine archer, anchored near the bottom of the
 * screen. He slides horizontally to follow the player's finger (dodging
 * Ravan's incoming aayudha) and looses arrows upward on an automatic cadence.
 *
 * Drawn entirely from primitive shapes — a glowing aura, a saffron body, a
 * crowned head and a golden longbow — no imported art.
 */
class Ram(
    var x: Float,
    val y: Float,
    val radius: Float,
    private val minX: Float,
    private val maxX: Float
) {
    var targetX = x
    var lives = 3
    var maxLives = 3

    /** Counts down invulnerability frames after taking a hit (i-frames). */
    var invuln = 0

    private var bob = 0f

    private val aura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD }
    private val bowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GOLD
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val bowOval = RectF()

    fun reset(startX: Float) {
        x = startX; targetX = startX
        lives = maxLives
        invuln = 0
        bob = 0f
    }

    fun update() {
        // Smoothly chase the finger so movement feels weighty but responsive.
        x += (targetX - x) * 0.35f
        x = x.coerceIn(minX, maxX)
        bob += 0.18f
        if (invuln > 0) invuln--
    }

    fun moveTo(px: Float) {
        targetX = px.coerceIn(minX, maxX)
    }

    /** Tight hit circle, written into [out] as a square for cheap overlap tests. */
    fun bounds(out: RectF) {
        val r = radius * 0.62f
        out.set(x - r, y - r, x + r, y + r)
    }

    /** Returns true if the hit registered (i.e. Ram was not in i-frames). */
    fun takeHit(): Boolean {
        if (invuln > 0) return false
        lives--
        invuln = 70
        return true
    }

    fun draw(canvas: Canvas) {
        // Flicker while invulnerable so a hit reads clearly.
        if (invuln > 0 && (invuln / 4) % 2 == 0) return
        val yb = y + kotlin.math.sin(bob) * radius * 0.05f

        // Divine aura.
        aura.color = (0x55 shl 24) or (AURA and 0x00FFFFFF)
        canvas.drawCircle(x, yb, radius * 1.55f, aura)

        // Bow: an arc facing up toward Ravan, with a string.
        bowPaint.strokeWidth = radius * 0.13f
        val br = radius * 1.15f
        bowOval.set(x - br, yb - br, x + br, yb + br)
        canvas.drawArc(bowOval, -150f, 120f, false, bowPaint)
        // Bowstring between the two tips of the drawn arc.
        val a1 = Math.toRadians(-150.0); val a2 = Math.toRadians(-30.0)
        bowPaint.strokeWidth = radius * 0.05f
        canvas.drawLine(
            x + (br * kotlin.math.cos(a1)).toFloat(), yb + (br * kotlin.math.sin(a1)).toFloat(),
            x + (br * kotlin.math.cos(a2)).toFloat(), yb + (br * kotlin.math.sin(a2)).toFloat(),
            bowPaint
        )

        // Body: a saffron rounded capsule.
        body.color = SAFFRON
        val bw = radius * 0.7f
        canvas.drawRoundRect(
            x - bw, yb - radius * 0.1f, x + bw, yb + radius * 1.15f,
            bw, bw, body
        )

        // Head.
        body.color = SKIN
        val hy = yb - radius * 0.45f
        canvas.drawCircle(x, hy, radius * 0.42f, body)

        // A small three-point golden crown / tilak halo.
        path.reset()
        val cw = radius * 0.42f
        path.moveTo(x - cw, hy - radius * 0.3f)
        path.lineTo(x - cw * 0.5f, hy - radius * 0.6f)
        path.lineTo(x, hy - radius * 0.35f)
        path.lineTo(x + cw * 0.5f, hy - radius * 0.6f)
        path.lineTo(x + cw, hy - radius * 0.3f)
        path.close()
        canvas.drawPath(path, gold)
    }

    companion object {
        val AURA = 0xFF3AA0FF.toInt()
        val SAFFRON = 0xFFFF8A1E.toInt()
        val SKIN = 0xFFFFC98A.toInt()
        val GOLD = 0xFFFFD24A.toInt()
        @Suppress("unused") val WHITE = Color.WHITE
    }
}
