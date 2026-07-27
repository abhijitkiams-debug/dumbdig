package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * A duelling combatant standing on the terrain — Ram (player, faces right) or
 * Ravan (the ten-headed boss, faces left), drawn in a familiar, lightly
 * anime-styled side view: big expressive eyes, golden crowns, and iconography
 * (Ram blue-skinned with a tilak; Ravan's ten fierce heads). Ram can be
 * equipped with unlockable golden Armour (+health) and a Rath / chariot.
 */
class Fighter(
    val isRam: Boolean,
    var x: Float,
    var feetY: Float,
    val scale: Float,
    var maxHp: Float
) {
    var hp = maxHp
    var hurt = 0f
    var armour = false
    var rath = false
    // Enemy appearance (ignored for Ram).
    var skin = 0xFF4A2036.toInt()
    var heads = 10
    var crown = true
    var displayName = if (isRam) "RAM" else "RAVAN"
    private val facing = if (isRam) 1f else -1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val oval = RectF()

    private fun rathLift() = if (rath) scale * 0.42f else 0f
    private fun baseY() = feetY - rathLift()

    fun muzzleX() = x + facing * scale * 0.4f
    fun muzzleY() = baseY() - scale * 1.05f
    fun centerY() = baseY() - scale * 0.85f
    fun bodyRadius() = scale * 0.72f

    fun hitBy(px: Float, py: Float, blast: Float): Float {
        val d = kotlin.math.hypot(px - x, py - centerY())
        val reach = bodyRadius() + blast
        return if (d < reach) (1f - d / reach).coerceIn(0f, 1f) else 0f
    }

    fun update() { if (hurt > 0f) hurt -= 1f }

    fun draw(canvas: Canvas) {
        if (rath && isRam) drawRath(canvas)
        if (isRam) drawRam(canvas) else drawEnemy(canvas)
        if (hurt > 0f) {
            p.color = ((0x66 * (hurt / 12f)).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
            canvas.drawCircle(x, centerY(), bodyRadius() * 1.4f, p)
        }
    }

    // ------------------------------------------------------------ anime eyes
    private fun eye(canvas: Canvas, ex: Float, ey: Float, r: Float, iris: Int, angry: Boolean) {
        p.color = Color.WHITE
        oval.set(ex - r, ey - r * 0.8f, ex + r, ey + r * 0.8f)
        canvas.drawOval(oval, p)
        p.color = iris
        canvas.drawCircle(ex + facing * r * 0.2f, ey, r * 0.55f, p)
        p.color = Color.BLACK
        canvas.drawCircle(ex + facing * r * 0.2f, ey, r * 0.28f, p)
        p.color = Color.WHITE
        canvas.drawCircle(ex + facing * r * 0.05f, ey - r * 0.25f, r * 0.14f, p)
        // Brow.
        stroke.color = if (angry) 0xFF3A0A0A.toInt() else 0xFF2A1A0A.toInt()
        stroke.strokeWidth = r * 0.28f; stroke.strokeCap = Paint.Cap.ROUND
        if (angry) canvas.drawLine(ex - r, ey - r * 0.7f, ex + r, ey - r * 1.1f, stroke)
        else canvas.drawLine(ex - r, ey - r * 1.0f, ex + r, ey - r * 0.9f, stroke)
    }

    // ------------------------------------------------------------------ RAM
    private fun drawRam(canvas: Canvas) {
        val s = scale; val by = baseY()
        p.color = 0x333AA0FF
        canvas.drawCircle(x, by - s * 0.85f, s * 1.35f, p)
        // Legs.
        stroke.color = 0xFF8FC4F2.toInt(); stroke.strokeWidth = s * 0.16f; stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, by - s * 0.5f, x - s * 0.22f, by, stroke)
        canvas.drawLine(x, by - s * 0.5f, x + s * 0.22f, by, stroke)
        // Dhoti.
        p.color = Palette.SAFFRON
        oval.set(x - s * 0.32f, by - s * 0.95f, x + s * 0.32f, by - s * 0.3f)
        canvas.drawRoundRect(oval, s * 0.2f, s * 0.2f, p)
        // Torso (blue skin) or golden armour.
        if (armour) {
            p.color = Palette.GOLD
            oval.set(x - s * 0.34f, by - s * 1.28f, x + s * 0.34f, by - s * 0.8f)
            canvas.drawRoundRect(oval, s * 0.16f, s * 0.16f, p)
            p.color = 0xFFB8860B.toInt()
            canvas.drawLine(x - s * 0.34f, by - s * 1.04f, x + s * 0.34f, by - s * 1.04f, linePaint(s * 0.05f))
            // Shoulder guards.
            p.color = Palette.GOLD
            canvas.drawCircle(x - s * 0.34f, by - s * 1.2f, s * 0.14f, p)
            canvas.drawCircle(x + s * 0.34f, by - s * 1.2f, s * 0.14f, p)
        } else {
            p.color = 0xFF7EBEF5.toInt()
            oval.set(x - s * 0.3f, by - s * 1.25f, x + s * 0.3f, by - s * 0.8f)
            canvas.drawRoundRect(oval, s * 0.18f, s * 0.18f, p)
        }
        // Arms holding the bow.
        stroke.color = 0xFF7EBEF5.toInt(); stroke.strokeWidth = s * 0.11f
        canvas.drawLine(x, by - s * 1.05f, x + facing * s * 0.5f, by - s * 1.0f, stroke)
        // --- Head: drawn for guaranteed identity readability ---
        // Slightly larger, near-front so BOTH eyes and the Tilak always show.
        val hx = x; val hy = by - s * 1.5f; val hr = s * 0.30f
        // Dark contrast rim so the face reads against any (golden) background.
        p.color = 0xFF20304A.toInt(); canvas.drawCircle(hx, hy, hr * 1.08f, p)
        p.color = 0xFF8FC4F2.toInt(); canvas.drawCircle(hx, hy, hr, p)

        // Golden crown (mukut) BEHIND the forehead so it never occludes eyes/Tilak.
        p.color = Palette.GOLD
        path.reset()
        path.moveTo(hx - hr * 0.95f, hy - hr * 0.55f)
        path.lineTo(hx - hr * 0.55f, hy - hr * 1.55f)
        path.lineTo(hx - hr * 0.18f, hy - hr * 0.85f)
        path.lineTo(hx, hy - hr * 1.7f)
        path.lineTo(hx + hr * 0.18f, hy - hr * 0.85f)
        path.lineTo(hx + hr * 0.55f, hy - hr * 1.55f)
        path.lineTo(hx + hr * 0.95f, hy - hr * 0.55f)
        path.close()
        canvas.drawPath(path, p)
        p.color = Palette.DEMON; canvas.drawCircle(hx, hy - hr * 1.15f, hr * 0.13f, p)

        // BOTH eyes, large, distinct, symmetric — non-negotiable identity.
        val er = hr * 0.30f
        eye(canvas, hx - hr * 0.4f, hy + hr * 0.02f, er, 0xFF3A2A6A.toInt(), false)
        eye(canvas, hx + hr * 0.4f, hy + hr * 0.02f, er, 0xFF3A2A6A.toInt(), false)
        // Gentle smile.
        stroke.color = 0xFF5A3A2A.toInt(); stroke.strokeWidth = hr * 0.12f; stroke.strokeCap = Paint.Cap.ROUND
        oval.set(hx - hr * 0.3f, hy + hr * 0.3f, hx + hr * 0.3f, hy + hr * 0.72f)
        canvas.drawArc(oval, 20f, 140f, false, stroke)

        // Bow, facing the enemy (drawn before the Tilak pass).
        stroke.color = Palette.GOLD; stroke.strokeWidth = s * 0.09f
        oval.set(x + facing * s * 0.15f - s * 0.55f, by - s * 1.5f, x + facing * s * 0.15f + s * 0.55f, by - s * 0.55f)
        canvas.drawArc(oval, -60f, 120f, false, stroke)

        // --- TILAK: drawn LAST, fully opaque, high contrast — never washed out ---
        drawTilak(canvas, hx, hy, hr)
    }

    /** The traditional Vaishnava U-tilak: a bold white U with a saffron centre. */
    private fun drawTilak(canvas: Canvas, hx: Float, hy: Float, hr: Float) {
        val top = hy - hr * 0.62f; val bot = hy - hr * 0.02f; val half = hr * 0.2f
        stroke.color = Color.WHITE; stroke.strokeWidth = hr * 0.14f; stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(hx - half, top, hx - half, bot - half * 0.4f, stroke)
        canvas.drawLine(hx + half, top, hx + half, bot - half * 0.4f, stroke)
        oval.set(hx - half, bot - half, hx + half, bot + half * 0.4f)
        canvas.drawArc(oval, 20f, 140f, false, stroke)
        // Saffron centre drop, fully opaque.
        stroke.color = Palette.SAFFRON; stroke.strokeWidth = hr * 0.12f
        canvas.drawLine(hx, top + hr * 0.06f, hx, bot - half * 0.3f, stroke)
    }

    private fun linePaint(wdt: Float): Paint {
        stroke.style = Paint.Style.STROKE; stroke.strokeWidth = wdt; stroke.color = p.color
        return stroke
    }

    // ---------------------------------------------------------------- RATH
    private fun drawRath(canvas: Canvas) {
        val s = scale
        // Horse silhouette in front.
        p.color = 0xFF6E5030.toInt()
        oval.set(x + s * 0.5f, feetY - s * 0.7f, x + s * 1.5f, feetY - s * 0.2f)
        canvas.drawRoundRect(oval, s * 0.2f, s * 0.2f, p)
        canvas.drawCircle(x + s * 1.5f, feetY - s * 0.78f, s * 0.22f, p)   // horse head
        // Horse legs.
        stroke.color = 0xFF5A4028.toInt(); stroke.strokeWidth = s * 0.07f; stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + s * 0.7f, feetY - s * 0.25f, x + s * 0.7f, feetY, stroke)
        canvas.drawLine(x + s * 1.3f, feetY - s * 0.25f, x + s * 1.3f, feetY, stroke)
        // Chariot deck.
        p.color = Palette.GOLD
        oval.set(x - s * 0.6f, feetY - s * 0.55f, x + s * 0.6f, feetY - s * 0.25f)
        canvas.drawRoundRect(oval, s * 0.1f, s * 0.1f, p)
        p.color = 0xFF8A5A1A.toInt()
        canvas.drawRect(x - s * 0.6f, feetY - s * 0.3f, x + s * 0.6f, feetY - s * 0.1f, p)
        // Wheel.
        p.color = 0xFF3A2716.toInt()
        canvas.drawCircle(x - s * 0.2f, feetY - s * 0.02f, s * 0.28f, p)
        p.color = Palette.GOLD
        canvas.drawCircle(x - s * 0.2f, feetY - s * 0.02f, s * 0.1f, p)
        stroke.color = Palette.GOLD; stroke.strokeWidth = s * 0.03f
        for (a in 0 until 6) {
            val ang = Math.toRadians((a * 60).toDouble())
            canvas.drawLine(x - s * 0.2f, feetY - s * 0.02f,
                x - s * 0.2f + kotlin.math.cos(ang).toFloat() * s * 0.28f,
                feetY - s * 0.02f + kotlin.math.sin(ang).toFloat() * s * 0.28f, stroke)
        }
    }

    // ---------------------------------------------------------------- ENEMY
    private fun drawEnemy(canvas: Canvas) {
        val s = scale; val by = feetY
        // Body.
        p.color = darken(skin, 0.45f)
        oval.set(x - s * 0.55f, by - s * 1.15f, x + s * 0.55f, by)
        canvas.drawRoundRect(oval, s * 0.24f, s * 0.24f, p)
        // Sash + belt.
        p.color = 0xFF7A1F2B.toInt()
        canvas.drawRect(x - s * 0.55f, by - s * 0.75f, x + s * 0.55f, by - s * 0.6f, p)
        p.color = Palette.GOLD
        canvas.drawRect(x - s * 0.55f, by - s * 0.62f, x + s * 0.55f, by - s * 0.55f, p)
        // Big curved sword.
        stroke.color = 0xFFC9CDD6.toInt(); stroke.strokeWidth = s * 0.09f; stroke.strokeCap = Paint.Cap.ROUND
        val sx = x + facing * s * 0.55f
        canvas.drawLine(sx, by - s * 0.9f, sx + facing * s * 0.5f, by - s * 1.5f, stroke)

        if (heads >= 10) {
            // Ravan: a fan of nine smaller heads + one crowned central head.
            for (i in 0 until 9) {
                val f = (i - 4f) / 4f
                val hx = x + f * s * 0.66f
                val hy = by - s * 1.28f - (1f - f * f) * s * 0.22f
                drawDemonHead(canvas, hx, hy, s * 0.15f, false)
            }
            drawDemonHead(canvas, x, by - s * 1.62f, s * 0.26f, true)
        } else {
            drawDemonHead(canvas, x, by - s * 1.5f, s * 0.32f, true)
        }
    }

    private fun darken(c: Int, f: Float): Int {
        val r = ((c shr 16 and 0xFF) * f).toInt(); val g = ((c shr 8 and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawDemonHead(canvas: Canvas, hx: Float, hy: Float, hr: Float, main: Boolean) {
        // Crown (only if this foe wears one, or always for Ravan's heads).
        if (crown || heads >= 10) {
            p.color = Palette.GOLD
            path.reset()
            path.moveTo(hx - hr * 0.9f, hy - hr * 0.6f)
            path.lineTo(hx - hr * 0.5f, hy - hr * 1.5f)
            path.lineTo(hx, hy - hr * 0.8f)
            path.lineTo(hx + hr * 0.5f, hy - hr * 1.5f)
            path.lineTo(hx + hr * 0.9f, hy - hr * 0.6f)
            path.close()
            canvas.drawPath(path, p)
        }
        // Face.
        p.color = if (main) skin else darken(skin, 0.8f)
        canvas.drawCircle(hx, hy, hr, p)
        // Fierce eyes.
        val er = hr * 0.36f
        eye(canvas, hx - hr * 0.4f, hy - hr * 0.05f, er, Palette.DEMON, true)
        eye(canvas, hx + hr * 0.4f, hy - hr * 0.05f, er, Palette.DEMON, true)
        // Third eye on the main head.
        if (main) { p.color = Palette.GOLD; canvas.drawCircle(hx, hy - hr * 0.55f, hr * 0.12f, p) }
        // Mustache + fanged mouth.
        stroke.color = Color.BLACK; stroke.strokeWidth = hr * 0.14f
        canvas.drawLine(hx - hr * 0.5f, hy + hr * 0.45f, hx + hr * 0.5f, hy + hr * 0.45f, stroke)
        p.color = Color.WHITE
        for (k in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(hx + k * hr * 0.25f, hy + hr * 0.45f)
            path.lineTo(hx + k * hr * 0.4f, hy + hr * 0.45f)
            path.lineTo(hx + k * hr * 0.32f, hy + hr * 0.75f)
            path.close()
            canvas.drawPath(path, p)
        }
    }

    fun drawHealthBar(canvas: Canvas, text: Paint) {
        val bw = scale * 1.5f; val bh = scale * 0.14f
        val left = x - bw / 2f; val top = baseY() - scale * 2.05f
        p.color = 0x66000000
        canvas.drawRoundRect(left - bh * 0.3f, top - bh * 0.3f, left + bw + bh * 0.3f, top + bh + bh * 0.3f, bh, bh, p)
        p.color = 0xFF333333.toInt()
        canvas.drawRoundRect(left, top, left + bw, top + bh, bh / 2f, bh / 2f, p)
        val frac = (hp / maxHp).coerceIn(0f, 1f)
        p.color = if (isRam) Palette.GRASS else Palette.DEMON
        canvas.drawRoundRect(left, top, left + bw * frac, top + bh, bh / 2f, bh / 2f, p)
        text.color = Color.WHITE; text.textSize = bh * 0.9f; text.textAlign = Paint.Align.CENTER
        canvas.drawText(displayName, x, top - bh * 0.5f, text)
    }
}
