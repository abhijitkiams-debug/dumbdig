package com.originalgames.dhishoom

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Builds and shares a square "result card" image after a match. A clean,
 * screenshot-ready card is the engine of the share-to-social loop: the player
 * taps SHARE, the system sheet opens, and a branded image with their result
 * lands in a chat or story to bait a rematch.
 *
 * Drawn entirely from primitives — no template image is bundled.
 */
object ShareCard {

    private const val SIZE = 1080

    fun render(
        won: Boolean,
        fighter: FighterArchetype,
        opponent: FighterArchetype,
        roundsWon: Int,
        roundsLost: Int,
        bestCombo: Int,
        mode: String
    ): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = SIZE / 2f

        c.drawColor(Color.parseColor("#120A14"))
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1220") }
        val inset = SIZE * 0.06f
        c.drawRoundRect(inset, inset, SIZE - inset, SIZE - inset, 48f, 48f, panel)

        val bold = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textAlign = Paint.Align.CENTER
        }

        // Title.
        text.color = Color.parseColor("#FFD25A")
        text.textSize = SIZE * 0.085f
        c.drawText("DHISHOOM", cx, SIZE * 0.18f, text)

        text.color = 0x88FFFFFF.toInt()
        text.textSize = SIZE * 0.034f
        c.drawText(mode, cx, SIZE * 0.235f, text)

        // Verdict.
        text.color = if (won) Color.parseColor("#22E0C8") else Color.parseColor("#FF4D5B")
        text.textSize = SIZE * 0.14f
        c.drawText(if (won) "VICTORY" else "DEFEAT", cx, SIZE * 0.40f, text)

        // Fighters line.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        val you = SIZE * 0.34f
        val them = SIZE * 0.66f
        val fy = SIZE * 0.55f
        glow.color = fighter.body; c.drawCircle(you, fy, SIZE * 0.06f, glow)
        glow.color = opponent.body; c.drawCircle(them, fy, SIZE * 0.06f, glow)
        text.color = Color.WHITE
        text.textSize = SIZE * 0.038f
        c.drawText(fighter.name, you, fy + SIZE * 0.11f, text)
        c.drawText(opponent.name, them, fy + SIZE * 0.11f, text)
        text.color = 0x66FFFFFF.toInt()
        text.textSize = SIZE * 0.05f
        c.drawText("VS", cx, fy + SIZE * 0.02f, text)

        // Score line.
        text.color = Color.WHITE
        text.textSize = SIZE * 0.07f
        c.drawText("$roundsWon  –  $roundsLost", cx, SIZE * 0.78f, text)

        text.color = Color.parseColor("#FFD25A")
        text.textSize = SIZE * 0.036f
        c.drawText("best combo  x$bestCombo", cx, SIZE * 0.83f, text)

        // Call to action.
        text.color = Color.parseColor("#FF4D5B")
        text.textSize = SIZE * 0.05f
        c.drawText("CAN YOU BEAT ME?", cx, SIZE * 0.90f, text)

        return bmp
    }

    fun share(
        context: Context,
        won: Boolean,
        fighter: FighterArchetype,
        opponent: FighterArchetype,
        roundsWon: Int,
        roundsLost: Int,
        bestCombo: Int,
        mode: String
    ) {
        try {
            val bmp = render(won, fighter, opponent, roundsWon, roundsLost, bestCombo, mode)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "dhishoom_result.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val verdict = if (won) "won" else "lost"
            val caption =
                "I $verdict $roundsWon–$roundsLost as ${fighter.name} in Dhishoom (best combo x$bestCombo). Quick fight, no internet — can you beat me?"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share your result")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            // Sharing is a bonus; never let it crash the game.
        }
    }
}
