package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * The boss: Ravan, the ten-headed Demon-King of Lanka, looming across the top
 * of the screen. He has [headsAlive] heads (0..10); Ram's arrows whittle down
 * the current head's health until it is severed, then the next becomes active.
 * Felling all ten is the victory milestone; in Rage mode he regrows them with
 * tougher health for endless score-chasing.
 *
 * Rendered entirely from primitives — a dark crowned body and a fan of fanged,
 * red-eyed heads — no imported art.
 */
class Ravan(
    private val w: Float,
    private val h: Float
) {
    var headsAlive = 10
        private set
    var hpTier = 0
        private set

    private val cy = h * 0.135f
    private var cx = w / 2f
    private var sway = 0f
    private var shake = 0f

    var activeHeadHp = 0f
        private set
    var activeHeadMaxHp = 0f
        private set

    private val headR = w * 0.052f
    private val spreadX = w * 0.40f
    private val arcDip = h * 0.05f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    init { refillHead() }

    /** Leftmost surviving head index (0..9); this is the one taking damage. */
    val activeIndex: Int get() = 10 - headsAlive

    fun reset() {
        headsAlive = 10
        hpTier = 0
        sway = 0f
        shake = 0f
        refillHead()
    }

    private fun baseHeadHp(): Float = (10f + activeIndex * 2.2f) * (1f + hpTier * 0.6f)

    private fun refillHead() {
        activeHeadMaxHp = baseHeadHp()
        activeHeadHp = activeHeadMaxHp
    }

    fun update() {
        sway += 0.03f
        cx = w / 2f + sin(sway) * w * 0.06f
        if (shake > 0f) shake *= 0.85f
    }

    private fun headPos(i: Int): FloatArray {
        val f = (i - 4.5f) / 4.5f          // -1..1 across the fan
        val hx = cx + f * spreadX
        val hy = cy + f * f * arcDip
        return floatArrayOf(hx, hy)
    }

    /** Centre of the currently-active head, where Ram should aim. */
    fun activeHeadX(): Float = headPos(activeIndex.coerceIn(0, 9))[0]
    fun activeHeadY(): Float = headPos(activeIndex.coerceIn(0, 9))[1]

    /** Band across the heads; an arrow entering it counts as striking Ravan. */
    fun strikeZone(out: RectF) {
        out.set(0f, cy - headR * 2f, w, cy + arcDip + headR * 2.2f)
    }

    /** Apply [d] damage to the active head. Returns true if a head was severed. */
    fun damage(d: Float): Boolean {
        if (headsAlive <= 0) return false
        activeHeadHp -= d
        shake = headR * 0.5f
        if (activeHeadHp <= 0f) {
            headsAlive--
            if (headsAlive > 0) refillHead()
            return true
        }
        return false
    }

    /** Bring all ten heads back for the next, tougher cycle (Rage mode). */
    fun regrow() {
        headsAlive = 10
        hpTier++
        refillHead()
    }

    fun draw(canvas: Canvas, rage: Boolean) {
        val sx = if (shake > 1f) (Math.random().toFloat() - 0.5f) * shake * 2f else 0f

        // Body: a broad dark mass beneath the heads.
        body.color = if (rage) 0xFF2A0E16.toInt() else 0xFF1A1320.toInt()
        canvas.drawRoundRect(
            cx - spreadX * 1.25f + sx, -h * 0.05f,
            cx + spreadX * 1.25f + sx, cy + arcDip + headR * 1.2f,
            headR, headR, body
        )
        // Shoulder crown band.
        accent.color = 0xFF7A1F2B.toInt()
        canvas.drawRect(cx - spreadX * 1.25f + sx, cy + arcDip + headR * 0.6f,
            cx + spreadX * 1.25f + sx, cy + arcDip + headR * 1.2f, accent)

        for (i in 0 until 10) {
            val p = headPos(i); val hx = p[0] + sx; val hy = p[1]
            if (i < activeIndex) drawSeveredStump(canvas, hx, hy)
            else drawHead(canvas, hx, hy, active = (i == activeIndex), rage = rage)
        }

        // Active-head health bar.
        if (headsAlive > 0) {
            val frac = (activeHeadHp / activeHeadMaxHp).coerceIn(0f, 1f)
            val bw = w * 0.5f; val bh = h * 0.012f
            val left = cx - bw / 2f + sx; val top = cy - arcDip - headR * 1.6f
            stroke.color = 0x66FFFFFF
            stroke.strokeWidth = h * 0.002f
            canvas.drawRoundRect(left, top, left + bw, top + bh, bh / 2f, bh / 2f, stroke)
            body.color = 0xFFFF4D5E.toInt()
            canvas.drawRoundRect(left, top, left + bw * frac, top + bh, bh / 2f, bh / 2f, body)
        }
    }

    private fun drawHead(canvas: Canvas, hx: Float, hy: Float, active: Boolean, rage: Boolean) {
        if (active) {
            accent.color = 0x55FF4D5E.toInt()
            canvas.drawCircle(hx, hy, headR * 1.5f, accent)
        }
        // Skull.
        body.color = if (rage) 0xFF4A1A22.toInt() else 0xFF3A2230.toInt()
        canvas.drawCircle(hx, hy, headR, body)
        // Horns / crown spikes.
        accent.color = Ram.GOLD
        path.reset()
        path.moveTo(hx - headR * 0.7f, hy - headR * 0.6f)
        path.lineTo(hx - headR * 0.95f, hy - headR * 1.4f)
        path.lineTo(hx - headR * 0.35f, hy - headR * 0.85f)
        path.moveTo(hx + headR * 0.7f, hy - headR * 0.6f)
        path.lineTo(hx + headR * 0.95f, hy - headR * 1.4f)
        path.lineTo(hx + headR * 0.35f, hy - headR * 0.85f)
        path.close()
        canvas.drawPath(path, accent)
        // Eyes.
        eye.color = if (active) 0xFFFFE14A.toInt() else 0xFFFF3030.toInt()
        canvas.drawCircle(hx - headR * 0.38f, hy - headR * 0.1f, headR * 0.2f, eye)
        canvas.drawCircle(hx + headR * 0.38f, hy - headR * 0.1f, headR * 0.2f, eye)
        // Fanged mouth.
        body.color = Color.BLACK
        canvas.drawRoundRect(hx - headR * 0.45f, hy + headR * 0.3f,
            hx + headR * 0.45f, hy + headR * 0.6f, headR * 0.1f, headR * 0.1f, body)
        eye.color = Color.WHITE
        for (k in -1..1) {
            val fx = hx + k * headR * 0.3f
            path.reset()
            path.moveTo(fx - headR * 0.08f, hy + headR * 0.3f)
            path.lineTo(fx + headR * 0.08f, hy + headR * 0.3f)
            path.lineTo(fx, hy + headR * 0.55f)
            path.close()
            canvas.drawPath(path, eye)
        }
    }

    private fun drawSeveredStump(canvas: Canvas, hx: Float, hy: Float) {
        accent.color = 0x33FF4D5E.toInt()
        canvas.drawCircle(hx, hy, headR * 0.5f, accent)
        stroke.color = 0x55FF6A6A.toInt()
        stroke.strokeWidth = headR * 0.12f
        canvas.drawLine(hx - headR * 0.4f, hy, hx + headR * 0.4f, hy, stroke)
    }
}
