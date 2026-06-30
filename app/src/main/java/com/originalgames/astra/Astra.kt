package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.sin

/**
 * The four divine astras Ram can invoke once his astra meter is full. Firing
 * one cycles to the next, so a skilled run rotates through the whole arsenal:
 *
 *  - AGNEYA  (fire)    — a wall of flame sweeps up, burning incoming aayudha
 *                        and scorching Ravan.
 *  - VAYAVYA (wind)    — a gale blows every incoming weapon off the screen.
 *  - NAGA    (serpent) — a volley of homing serpent-arrows seeks Ravan.
 *  - BRAHMA  (ultimate)— a pillar of light clears the field and devastates Ravan.
 *
 * [AstraType] holds the metadata; [ActiveAstra] is the short-lived on-screen
 * visual, drawn from primitives.
 */
enum class AstraType(val short: String, val full: String, val color: Int) {
    AGNEYA("AGNEYA", "Agneyastra", 0xFFFF6A1E.toInt()),
    VAYAVYA("VAYAVYA", "Vayavyastra", 0xFF7FE0FF.toInt()),
    NAGA("NAGA", "Nagastra", 0xFF49E07A.toInt()),
    BRAHMA("BRAHMA", "Brahmastra", 0xFFFFE14A.toInt());

    fun next(): AstraType = entries[(ordinal + 1) % entries.size]
}

/** A transient full-field visual effect for a fired astra. */
class ActiveAstra(val type: AstraType, val originX: Float, val originY: Float) {
    var age = 0
    val maxAge = when (type) {
        AstraType.AGNEYA -> 42
        AstraType.VAYAVYA -> 30
        AstraType.NAGA -> 18
        AstraType.BRAHMA -> 48
    }
    val alive: Boolean get() = age < maxAge

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update() { age++ }

    fun draw(canvas: Canvas, w: Float, h: Float) {
        val p = age.toFloat() / maxAge
        when (type) {
            AstraType.AGNEYA -> drawAgneya(canvas, w, h, p)
            AstraType.VAYAVYA -> drawVayavya(canvas, w, h, p)
            AstraType.NAGA -> drawNaga(canvas, p)
            AstraType.BRAHMA -> drawBrahma(canvas, w, h, p)
        }
    }

    private fun drawAgneya(canvas: Canvas, w: Float, h: Float, p: Float) {
        val edge = originY - p * (originY + h * 0.1f)
        paint.color = (0x66 shl 24) or (AstraType.AGNEYA.color and 0x00FFFFFF)
        canvas.drawRect(0f, edge, w, h, paint)
        paint.color = 0xCCFFC23A.toInt()
        var x = 0f
        val step = w * 0.06f
        while (x < w) {
            val fy = edge + sin((x * 0.05f) + age) * h * 0.02f
            canvas.drawCircle(x, fy, w * 0.04f * (0.6f + (1f - p)), paint)
            x += step
        }
    }

    private fun drawVayavya(canvas: Canvas, w: Float, h: Float, p: Float) {
        paint.color = ((0x55 * (1f - p)).toInt() shl 24) or (AstraType.VAYAVYA.color and 0x00FFFFFF)
        paint.strokeWidth = h * 0.006f
        var y = h
        val gap = h * 0.05f
        while (y > 0) {
            val off = (1f - p) * w
            canvas.drawLine(off + (y % gap), y, off + w * 0.4f + (y % gap), y, paint)
            y -= gap
        }
    }

    private fun drawNaga(canvas: Canvas, p: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = originX * 0.02f + 6f
        paint.color = ((0xAA * (1f - p)).toInt() shl 24) or (AstraType.NAGA.color and 0x00FFFFFF)
        canvas.drawCircle(originX, originY, p * originX * 0.9f + 10f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBrahma(canvas: Canvas, w: Float, h: Float, p: Float) {
        // Early white flash.
        if (p < 0.25f) {
            paint.color = ((0xCC * (1f - p / 0.25f)).toInt() shl 24) or 0x00FFFFFF
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        // Pillar of light from Ram to Ravan.
        val halfW = w * (0.18f * (1f - kotlin.math.abs(p - 0.5f) * 2f) + 0.04f)
        paint.color = 0x88FFE14A.toInt()
        canvas.drawRect(originX - halfW, 0f, originX + halfW, originY, paint)
        paint.color = Color.WHITE
        canvas.drawRect(originX - halfW * 0.35f, 0f, originX + halfW * 0.35f, originY, paint)
    }
}
