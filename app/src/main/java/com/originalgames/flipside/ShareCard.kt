package com.originalgames.flipside

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Builds and shares a square "result card" image after a run. A clean,
 * screenshot-ready card is the engine of the share-to-social loop: the player
 * taps SHARE, the system sheet opens, and a branded image with their score
 * lands in a chat or story.
 *
 * The card is drawn entirely from primitives — no template image is bundled.
 */
object ShareCard {

    private const val SIZE = 1080

    fun render(score: Int, gems: Int, daily: Boolean, dateLabel: String, skin: Skin, streak: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = SIZE / 2f

        // Background.
        c.drawColor(Color.parseColor("#0E1116"))
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#151B24") }
        val inset = SIZE * 0.06f
        c.drawRoundRect(inset, inset, SIZE - inset, SIZE - inset, 48f, 48f, panel)

        val bold = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textAlign = Paint.Align.CENTER
        }

        // Title.
        text.color = Color.parseColor("#22E0C8")
        text.textSize = SIZE * 0.095f
        c.drawText("FLIPSIDE", cx, SIZE * 0.20f, text)

        // Mode tag.
        text.color = if (daily) Color.parseColor("#FFD25A") else 0x88FFFFFF.toInt()
        text.textSize = SIZE * 0.040f
        c.drawText(if (daily) "DAILY CHALLENGE • $dateLabel" else "ENDLESS RUN", cx, SIZE * 0.27f, text)

        if (daily && streak > 0) {
            text.color = Color.parseColor("#FF8C42")
            text.textSize = SIZE * 0.032f
            c.drawText("DAY STREAK $streak", cx, SIZE * 0.315f, text)
        }

        // The orb, in the player's equipped skin.
        val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (0x55 shl 24) or (skin.down and 0x00FFFFFF) }
        c.drawCircle(cx, SIZE * 0.40f, SIZE * 0.085f, glow)
        orbPaint.color = skin.down
        c.drawCircle(cx, SIZE * 0.40f, SIZE * 0.055f, orbPaint)
        orbPaint.color = Color.WHITE
        c.drawCircle(cx, SIZE * 0.40f + SIZE * 0.018f, SIZE * 0.018f, orbPaint)

        // Score (the hero number).
        text.color = Color.WHITE
        text.textSize = SIZE * 0.045f
        c.drawText("SCORE", cx, SIZE * 0.55f, text)
        text.textSize = SIZE * 0.22f
        c.drawText(score.toString(), cx, SIZE * 0.71f, text)

        // Gems collected this run, with a little diamond.
        val gemX = cx - SIZE * 0.06f
        val gemY = SIZE * 0.80f
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD25A") }
        val s = SIZE * 0.022f
        val path = Path().apply {
            moveTo(gemX, gemY - s); lineTo(gemX + s * 0.7f, gemY)
            lineTo(gemX, gemY + s); lineTo(gemX - s * 0.7f, gemY); close()
        }
        c.drawPath(path, gp)
        text.color = 0xCCFFFFFF.toInt()
        text.textSize = SIZE * 0.045f
        text.textAlign = Paint.Align.LEFT
        c.drawText(" × $gems", gemX + s, gemY + s * 0.7f, text)

        // Call to action.
        text.textAlign = Paint.Align.CENTER
        text.color = Color.parseColor("#FF4D7A")
        text.textSize = SIZE * 0.05f
        c.drawText("CAN YOU BEAT ME?", cx, SIZE * 0.90f, text)

        return bmp
    }

    /** Renders the card and opens the Android share sheet with it attached. */
    fun share(context: Context, score: Int, gems: Int, daily: Boolean, dateLabel: String, skin: Skin, streak: Int) {
        try {
            val bmp = render(score, gems, daily, dateLabel, skin, streak)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "flipside_score.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val caption = if (daily) {
                val streakBit = if (streak > 1) " (day streak: $streak)" else ""
                "I scored $score on today's Flipside Daily Challenge ($dateLabel)$streakBit. Beat me!"
            } else {
                "I scored $score in Flipside. Can you beat me?"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share your score")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            // Sharing is a bonus; never let it crash the game.
        }
    }
}
