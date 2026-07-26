package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * A duelling combatant standing on the terrain — Ram (player, faces right) or
 * Ravan (the ten-headed boss, faces left). Drawn from primitives in a side-on
 * silhouette to match the classic Ram-vs-Ravan art style.
 */
class Fighter(
    val isRam: Boolean,
    var x: Float,
    var feetY: Float,
    val scale: Float,
    var maxHp: Float
) {
    var hp = maxHp
    var hurt = 0f                       // flash timer when struck
    private val facing = if (isRam) 1f else -1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val oval = RectF()

    /** Chest height — where projectiles are launched from and aimed at. */
    fun muzzleX() = x + facing * scale * 0.35f
    fun muzzleY() = feetY - scale * 1.05f
    fun centerY() = feetY - scale * 0.85f
    fun bodyRadius() = scale * 0.7f

    fun hitBy(px: Float, py: Float, blast: Float): Float {
        val d = kotlin.math.hypot(px - x, py - centerY())
        val reach = bodyRadius() + blast
        return if (d < reach) (1f - d / reach).coerceIn(0f, 1f) else 0f
    }

    fun update() { if (hurt > 0f) hurt -= 1f }

    fun draw(canvas: Canvas) {
        if (isRam) drawRam(canvas) else drawRavan(canvas)
        if (hurt > 0f) {
            p.color = ((0x66 * (hurt / 12f)).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
            canvas.drawCircle(x, centerY(), bodyRadius() * 1.4f, p)
        }
    }

    // ------------------------------------------------------------------ RAM
    private fun drawRam(canvas: Canvas) {
        val s = scale
        // Aura.
        p.color = 0x333AA0FF
        canvas.drawCircle(x, centerY(), s * 1.3f, p)
        // Legs.
        stroke.color = 0xFF8A5A2A.toInt(); stroke.strokeWidth = s * 0.16f; stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, feetY - s * 0.5f, x - s * 0.25f, feetY, stroke)
        canvas.drawLine(x, feetY - s * 0.5f, x + s * 0.25f, feetY, stroke)
        // Dhoti + torso.
        p.color = Palette.SAFFRON
        oval.set(x - s * 0.32f, feetY - s * 0.95f, x + s * 0.32f, feetY - s * 0.35f)
        canvas.drawRoundRect(oval, s * 0.2f, s * 0.2f, p)
        p.color = Palette.RAM_BLUE
        oval.set(x - s * 0.3f, feetY - s * 1.25f, x + s * 0.3f, feetY - s * 0.8f)
        canvas.drawRoundRect(oval, s * 0.18f, s * 0.18f, p)
        // Head + crown.
        p.color = Palette.SKIN
        canvas.drawCircle(x, feetY - s * 1.42f, s * 0.24f, p)
        p.color = Palette.GOLD
        path.reset()
        path.moveTo(x - s * 0.24f, feetY - s * 1.56f)
        path.lineTo(x - s * 0.12f, feetY - s * 1.78f)
        path.lineTo(x, feetY - s * 1.58f)
        path.lineTo(x + s * 0.12f, feetY - s * 1.78f)
        path.lineTo(x + s * 0.24f, feetY - s * 1.56f)
        path.close()
        canvas.drawPath(path, p)
        // Bow, facing the enemy.
        stroke.color = Palette.GOLD; stroke.strokeWidth = s * 0.09f
        oval.set(x + facing * s * 0.1f - s * 0.55f, feetY - s * 1.5f, x + facing * s * 0.1f + s * 0.55f, feetY - s * 0.55f)
        canvas.drawArc(oval, if (isRam) -60f else 120f, 120f, false, stroke)
    }

    // ---------------------------------------------------------------- RAVAN
    private fun drawRavan(canvas: Canvas) {
        val s = scale
        // Body.
        p.color = 0xFF2A1622.toInt()
        oval.set(x - s * 0.5f, feetY - s * 1.15f, x + s * 0.5f, feetY)
        canvas.drawRoundRect(oval, s * 0.24f, s * 0.24f, p)
        // Sash.
        p.color = 0xFF7A1F2B.toInt()
        canvas.drawRect(x - s * 0.5f, feetY - s * 0.75f, x + s * 0.5f, feetY - s * 0.6f, p)
        // Ten heads in a fan crown.
        val hr = s * 0.17f
        for (i in 0 until 10) {
            val f = (i - 4.5f) / 4.5f
            val hx = x + f * s * 0.62f
            val hy = feetY - s * 1.32f - (1f - f * f) * s * 0.28f
            p.color = 0xFF3A2230.toInt()
            canvas.drawCircle(hx, hy, hr, p)
            p.color = Palette.DEMON
            canvas.drawCircle(hx - hr * 0.3f, hy - hr * 0.1f, hr * 0.18f, p)
            canvas.drawCircle(hx + hr * 0.3f, hy - hr * 0.1f, hr * 0.18f, p)
            p.color = Palette.GOLD
            path.reset()
            path.moveTo(hx - hr * 0.6f, hy - hr * 0.7f)
            path.lineTo(hx, hy - hr * 1.5f)
            path.lineTo(hx + hr * 0.6f, hy - hr * 0.7f)
            path.close()
            canvas.drawPath(path, p)
        }
        // Big curved sword, facing Ram.
        stroke.color = 0xFFC9CDD6.toInt(); stroke.strokeWidth = s * 0.09f; stroke.strokeCap = Paint.Cap.ROUND
        val sx = x + facing * s * 0.55f
        canvas.drawLine(sx, feetY - s * 0.9f, sx + facing * s * 0.5f, feetY - s * 1.4f, stroke)
    }

    fun drawHealthBar(canvas: Canvas, text: Paint) {
        val bw = scale * 1.4f; val bh = scale * 0.14f
        val left = x - bw / 2f; val top = feetY - scale * 2.0f
        p.color = 0x66000000
        canvas.drawRoundRect(left - bh * 0.3f, top - bh * 0.3f, left + bw + bh * 0.3f, top + bh + bh * 0.3f, bh, bh, p)
        p.color = 0xFF333333.toInt()
        canvas.drawRoundRect(left, top, left + bw, top + bh, bh / 2f, bh / 2f, p)
        val frac = (hp / maxHp).coerceIn(0f, 1f)
        p.color = if (isRam) Palette.GRASS else Palette.DEMON
        canvas.drawRoundRect(left, top, left + bw * frac, top + bh, bh / 2f, bh / 2f, p)
        text.color = Color.WHITE
        text.textSize = bh * 0.9f
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(if (isRam) "RAM" else "RAVAN", x, top - bh * 0.5f, text)
    }
}
