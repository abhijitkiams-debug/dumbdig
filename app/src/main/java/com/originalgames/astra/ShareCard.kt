package com.originalgames.astra

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Builds and shares a square "result card" image after a battle. A clean,
 * screenshot-ready card is the engine of the share-to-social loop: the player
 * taps SHARE, the system sheet opens, and a branded image with their score and
 * heads-severed count lands in a chat or story.
 *
 * The card is drawn entirely from primitives — no template image is bundled.
 */
object ShareCard {

    private const val SIZE = 1080

    fun render(score: Int, heads: Int, daily: Boolean, dateLabel: String, streak: Int, slain: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = SIZE / 2f

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, SIZE.toFloat(),
                intArrayOf(0xFF1A0E22.toInt(), 0xFF2A1020.toInt(), 0xFF120A12.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bg)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161020.toInt() }
        val inset = SIZE * 0.05f
        c.drawRoundRect(inset, inset, SIZE - inset, SIZE - inset, 48f, 48f, panel)

        val bold = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }

        // Title.
        text.color = Ram.GOLD
        text.textSize = SIZE * 0.10f
        c.drawText("ASTRA", cx, SIZE * 0.18f, text)
        text.color = 0xFFFF6A6A.toInt()
        text.textSize = SIZE * 0.042f
        c.drawText("RAM  vs  RAVAN", cx, SIZE * 0.235f, text)

        // Mode tag.
        text.color = if (daily) Ram.GOLD else 0x88FFFFFF.toInt()
        text.textSize = SIZE * 0.034f
        c.drawText(if (daily) "DAILY BATTLE • $dateLabel" else "ENDLESS WAR", cx, SIZE * 0.295f, text)
        if (daily && streak > 0) {
            text.color = 0xFFFF8C42.toInt()
            text.textSize = SIZE * 0.030f
            c.drawText("DAY STREAK $streak", cx, SIZE * 0.335f, text)
        }

        // Heads severed — a row of small severed-head marks out of ten.
        val markY = SIZE * 0.43f
        val shown = heads.coerceAtMost(10)
        val gap = SIZE * 0.072f
        val startX = cx - gap * 4.5f
        val hp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 10) {
            val hx = startX + i * gap
            hp.color = if (i < shown) 0xFFFF4D5E.toInt() else 0x33FFFFFF
            c.drawCircle(hx, markY, SIZE * 0.022f, hp)
            if (i < shown) {
                hp.color = Ram.GOLD
                c.drawCircle(hx, markY, SIZE * 0.009f, hp)
            }
        }

        // Hero: heads severed count.
        text.color = Color.WHITE
        text.textSize = SIZE * 0.040f
        c.drawText("HEADS SEVERED", cx, SIZE * 0.52f, text)
        text.color = Ram.GOLD
        text.textSize = SIZE * 0.16f
        c.drawText(if (heads > 10) "$heads" else "$heads / 10", cx, SIZE * 0.65f, text)

        // Score.
        text.color = 0xCCFFFFFF.toInt()
        text.textSize = SIZE * 0.05f
        c.drawText("SCORE  $score", cx, SIZE * 0.73f, text)

        // A little bow-and-arrow glyph.
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Ram.GOLD; strokeWidth = SIZE * 0.01f; strokeCap = Paint.Cap.ROUND
        }
        val bowR = SIZE * 0.05f
        val oval = android.graphics.RectF(cx - bowR, SIZE * 0.80f - bowR, cx + bowR, SIZE * 0.80f + bowR)
        c.drawArc(oval, 120f, 120f, false, gp)
        gp.strokeWidth = SIZE * 0.004f
        c.drawLine(cx - bowR * 0.5f, SIZE * 0.80f - bowR * 0.86f, cx - bowR * 0.5f, SIZE * 0.80f + bowR * 0.86f, gp)
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val pth = Path().apply {
            moveTo(cx + bowR * 1.4f, SIZE * 0.80f)
            lineTo(cx + bowR * 0.9f, SIZE * 0.80f - SIZE * 0.012f)
            lineTo(cx + bowR * 0.9f, SIZE * 0.80f + SIZE * 0.012f)
            close()
        }
        c.drawPath(pth, arrow)

        // Call to action.
        text.color = if (slain) Ram.GOLD else 0xFFFF4D7A.toInt()
        text.textSize = SIZE * 0.048f
        c.drawText(if (slain) "RAVAN SLAIN — CAN YOU?" else "CAN YOU SLAY RAVAN?", cx, SIZE * 0.90f, text)

        return bmp
    }

    /** Renders the card and opens the Android share sheet with it attached. */
    fun share(context: Context, score: Int, heads: Int, daily: Boolean, dateLabel: String, streak: Int, slain: Boolean) {
        try {
            val bmp = render(score, heads, daily, dateLabel, streak, slain)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "astra_score.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val headsBit = if (heads > 10) "$heads heads" else "$heads of 10 heads"
            val caption = if (daily) {
                val streakBit = if (streak > 1) " (day streak: $streak)" else ""
                "I severed $headsBit of Ravan in today's Astra Daily Battle ($dateLabel)$streakBit. Can you beat me?"
            } else {
                "I severed $headsBit of Ravan in Astra: Ram vs Ravan. Can you beat me?"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share your battle")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            // Sharing is a bonus; never let it crash the game.
        }
    }
}
