package com.originalgames.astra

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.sin
import kotlin.random.Random

/**
 * A destructible 2D ground surface for the artillery duel, plus a few standing
 * obstacles (pillars). The surface is a sampled height array interpolated across
 * the screen width; explosions carve craters into it. Each battle generates a
 * fresh, varied terrain — the "different terrain" of the Pocket-Tanks loop.
 */
class Terrain(private val w: Float, private val h: Float, seed: Long) {

    private val cols = 160
    private val heights = FloatArray(cols)
    private val baseY = h * 0.72f
    private val minY = h * 0.42f
    private val maxY = h * 0.86f

    val obstacles = ArrayList<RectF>()

    private val soil = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.GRASS; strokeCap = Paint.Cap.ROUND
    }
    private val pillar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        val rng = Random(seed)
        val a1 = 0.06f + rng.nextFloat() * 0.05f
        val a2 = 0.03f + rng.nextFloat() * 0.04f
        val p1 = 1.5f + rng.nextFloat() * 2f
        val p2 = 3f + rng.nextFloat() * 3f
        val ph = rng.nextFloat() * 6.28f
        for (i in 0 until cols) {
            val t = i.toFloat() / (cols - 1)
            var y = baseY - (sin(t * p1 * Math.PI.toFloat() + ph) * a1 + sin(t * p2 * Math.PI.toFloat()) * a2) * h
            y += (rng.nextFloat() - 0.5f) * h * 0.02f
            heights[i] = y.coerceIn(minY, maxY)
        }
        // Flatten the two firing platforms so nobody stands on a cliff.
        flatten(0, 16); flatten(cols - 16, cols)

        // A few standing pillars between the fighters (indestructible cover).
        val n = 1 + rng.nextInt(3)
        repeat(n) {
            val cx = w * (0.32f + rng.nextFloat() * 0.36f)
            val pw = w * (0.02f + rng.nextFloat() * 0.02f)
            val topY = heightAt(cx) - h * (0.14f + rng.nextFloat() * 0.16f)
            obstacles.add(RectF(cx - pw, topY, cx + pw, heightAt(cx)))
        }
    }

    private fun flatten(from: Int, to: Int) {
        val lvl = heights[(from + to) / 2]
        for (i in from until to.coerceAtMost(cols)) heights[i] = lvl
    }

    fun heightAt(x: Float): Float {
        val t = (x / w).coerceIn(0f, 1f) * (cols - 1)
        val i = t.toInt().coerceIn(0, cols - 2)
        val f = t - i
        return heights[i] * (1 - f) + heights[i + 1] * f
    }

    /** Carve a crater around [cx] with pixel radius [r] (destructible terrain). */
    fun crater(cx: Float, r: Float) {
        val colW = w / (cols - 1)
        val span = (r / colW).toInt()
        val ci = (cx / w * (cols - 1)).toInt()
        for (i in (ci - span)..(ci + span)) {
            if (i < 0 || i >= cols) continue
            val dx = (i - ci) * colW
            val drop = kotlin.math.sqrt((r * r - dx * dx).coerceAtLeast(0f))
            heights[i] = (heights[i] + drop * 0.6f).coerceAtMost(maxY)
        }
    }

    /** True if [x],[y] is inside the ground or any pillar. */
    fun collides(x: Float, y: Float): Boolean {
        if (y >= heightAt(x)) return true
        for (o in obstacles) if (o.contains(x, y)) return true
        return false
    }

    fun draw(canvas: Canvas) {
        // Soil body.
        path.reset()
        path.moveTo(0f, h)
        path.lineTo(0f, heights[0])
        val colW = w / (cols - 1)
        for (i in 1 until cols) path.lineTo(i * colW, heights[i])
        path.lineTo(w, h)
        path.close()
        soil.color = Palette.SOIL
        canvas.drawPath(path, soil)

        // Grass edge along the surface.
        grassEdge.strokeWidth = h * 0.03f
        var i = 1
        while (i < cols) {
            canvas.drawLine((i - 1) * colW, heights[i - 1], i * colW, heights[i], grassEdge)
            i++
        }
        // Darker soil shading band just under the grass.
        i = 1
        while (i < cols) {
            canvas.drawLine((i - 1) * colW, heights[i - 1] + h * 0.03f, i * colW, heights[i] + h * 0.03f, grassEdgeDark)
            i++
        }

        // Pillars.
        for (o in obstacles) {
            pillar.color = 0xFF9A8C6E.toInt()
            canvas.drawRect(o, pillar)
            pillar.color = 0xFF6E6350.toInt()
            canvas.drawRect(o.left, o.top, o.left + o.width() * 0.28f, o.bottom, pillar)
            pillar.color = 0xFFBFB295.toInt()
            canvas.drawRect(o.left, o.top, o.right, o.top + h * 0.02f, pillar)
        }
    }

    private val grassEdgeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.GRASS_DARK; strokeCap = Paint.Cap.ROUND
        strokeWidth = h * 0.012f
    }
}
