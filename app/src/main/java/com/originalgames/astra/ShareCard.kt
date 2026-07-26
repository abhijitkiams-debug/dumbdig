package com.originalgames.astra

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Builds and shares a square result card after a duel. A clean, screenshot-ready
 * card is the engine of the share-to-social loop: tap SHARE, the system sheet
 * opens, and a branded image lands in a chat or story.
 *
 * Drawn entirely from primitives — no template image is bundled.
 */
object ShareCard {

    private const val SIZE = 1080

    fun render(won: Boolean, rounds: Int, damage: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = SIZE / 2f

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, SIZE.toFloat(),
                intArrayOf(0xFF5B3A8A.toInt(), 0xFFC9628A.toInt(), 0xFFF2A65A.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bg)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC161020.toInt() }
        val inset = SIZE * 0.05f
        c.drawRoundRect(inset, inset, SIZE - inset, SIZE - inset, 48f, 48f, panel)

        val bold = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }

        t.color = Palette.GOLD; t.textSize = SIZE * 0.11f
        c.drawText("ASTRA", cx, SIZE * 0.19f, t)
        t.color = Palette.DEMON; t.textSize = SIZE * 0.045f
        c.drawText("RAM  vs  RAVAN", cx, SIZE * 0.25f, t)

        t.color = if (won) Palette.GOLD else Palette.DEMON
        t.textSize = SIZE * 0.09f
        c.drawText(if (won) "RAVAN SLAIN!" else "RAM HAS FALLEN", cx, SIZE * 0.45f, t)

        // Simple bow-and-arrow glyph.
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Palette.GOLD; strokeWidth = SIZE * 0.01f; strokeCap = Paint.Cap.ROUND
        }
        val bowR = SIZE * 0.06f
        val oval = RectF(cx - bowR, SIZE * 0.58f - bowR, cx + bowR, SIZE * 0.58f + bowR)
        c.drawArc(oval, 120f, 120f, false, gp)
        gp.strokeWidth = SIZE * 0.006f
        c.drawLine(cx + bowR * 1.5f, SIZE * 0.58f, cx - bowR * 0.6f, SIZE * 0.58f, gp)

        t.color = 0xDDFFFFFF.toInt(); t.textSize = SIZE * 0.05f
        c.drawText("$rounds rounds   •   $damage damage", cx, SIZE * 0.74f, t)

        t.color = if (won) Palette.GOLD else 0xFFFF4D7A.toInt(); t.textSize = SIZE * 0.048f
        c.drawText(if (won) "CAN YOU SLAY HIM FASTER?" else "CAN YOU DEFEAT RAVAN?", cx, SIZE * 0.88f, t)

        return bmp
    }

    fun share(context: Context, won: Boolean, rounds: Int, damage: Int) {
        try {
            val bmp = render(won, rounds, damage)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "astra_result.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val caption = if (won)
                "I slayed Ravan in $rounds rounds in Astra: Ram vs Ravan! Can you do it faster?"
            else
                "Ravan defeated me in Astra: Ram vs Ravan. Think you can beat him?"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share your duel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }
}
