package com.originalgames.flipside

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The whole game lives here: a [SurfaceView] driven by its own render thread.
 *
 * FLIPSIDE is a one-tap endless runner. The orb is fixed horizontally while the
 * world scrolls left. Tapping flips gravity so the orb falls to the floor or the
 * ceiling; the goal is to thread spikes and grab gems for as long as possible.
 *
 * Everything drawn here is generated from primitive shapes and text — there are
 * no imported images, fonts, or sounds anywhere in the project.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { READY, PLAYING, PAUSED, GAME_OVER }

    private val prefs = Prefs(context)
    private val sound = SoundManager(prefs.soundEnabled)
    private val particles = ParticleSystem()

    private var thread: GameThread? = null
    private var state = State.READY

    // --- Geometry (set in surfaceChanged) ---
    private var w = 0f
    private var h = 0f
    private var floorY = 0f
    private var ceilingY = 0f
    private lateinit var player: Player

    // --- World state ---
    private val obstacles = ArrayList<Obstacle>()
    private val gems = ArrayList<Gem>()
    private var speed = 0f
    private var distance = 0f
    private var spawnAccumulator = 0f
    private var nextSpawnGap = 0f
    private var score = 0
    private var gemCount = 0
    private var bestScore = prefs.bestScore
    private var newRecord = false
    private var gameOverAt = 0L
    private var bgPhase = 0f

    // --- Paints (reused; never allocate in the loop) ---
    private val bgPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD
        )
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val playerBounds = RectF()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ----------------------------------------------------------------------
    // Surface lifecycle
    // ----------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread = GameThread(holder).also {
            it.running = true
            it.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        val margin = h * 0.10f
        ceilingY = margin
        floorY = h - margin
        val radius = min(w, h) * 0.035f
        player = Player(
            x = w * 0.28f,
            y = floorY - radius,
            radius = radius,
            floorY = floorY,
            ceilingY = ceilingY
        )
        resetWorld()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.let {
            it.running = false
            var retry = true
            while (retry) {
                try {
                    it.join(); retry = false
                } catch (_: InterruptedException) {
                }
            }
        }
        thread = null
    }

    /** Called from the Activity to suspend a run when the app goes to background. */
    fun onActivityPause() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun toggleSound(): Boolean {
        sound.enabled = !sound.enabled
        prefs.soundEnabled = sound.enabled
        return sound.enabled
    }

    // ----------------------------------------------------------------------
    // Input
    // ----------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        when (state) {
            State.READY -> startRun()
            State.PLAYING -> {
                player.flip()
                sound.flip()
            }
            State.PAUSED -> state = State.PLAYING
            State.GAME_OVER -> {
                // Small lockout so the crash tap doesn't instantly restart.
                if (System.currentTimeMillis() - gameOverAt > 600) {
                    state = State.READY
                    resetWorld()
                }
            }
        }
        return true
    }

    // ----------------------------------------------------------------------
    // Game flow
    // ----------------------------------------------------------------------

    private fun resetWorld() {
        if (!this::player.isInitialized) return
        obstacles.clear()
        gems.clear()
        particles.clear()
        player.reset()
        speed = baseSpeed()
        distance = 0f
        spawnAccumulator = 0f
        nextSpawnGap = w * 0.7f
        score = 0
        gemCount = 0
        newRecord = false
    }

    private fun startRun() {
        resetWorld()
        state = State.PLAYING
        sound.start()
    }

    private fun baseSpeed() = w * 0.0085f

    private fun update() {
        bgPhase += 1f
        particles.update()
        if (state != State.PLAYING) return

        // Difficulty curve: speed creeps up with distance, then plateaus.
        speed = min(baseSpeed() * 2.4f, baseSpeed() + distance * 0.0000016f * w)
        distance += speed
        player.update()

        // Spawning.
        spawnAccumulator += speed
        if (spawnAccumulator >= nextSpawnGap) {
            spawnAccumulator = 0f
            spawnPattern()
            // Gaps tighten as the run goes on, but never become unfair.
            val tighten = min(distance * 0.00004f, w * 0.28f)
            nextSpawnGap = (w * 0.62f - tighten).coerceAtLeast(w * 0.30f)
        }

        // Advance + cull obstacles.
        run {
            var i = obstacles.size - 1
            while (i >= 0) {
                val o = obstacles[i]
                o.update(speed)
                if (o.isOffScreen()) obstacles.removeAt(i)
                i--
            }
        }

        // Advance + cull gems, handle pickups.
        player.bounds(playerBounds)
        run {
            var i = gems.size - 1
            while (i >= 0) {
                val g = gems[i]
                g.update(speed)
                if (g.overlaps(playerBounds)) {
                    g.collected = true
                    gemCount++
                    particles.burst(g.x, g.y, Color.parseColor("#FFD25A"), 16, player.radius * 0.9f)
                    sound.gem()
                }
                if (g.collected || g.isOffScreen()) gems.removeAt(i)
                i--
            }
        }

        // Collision with hazards.
        for (o in obstacles) {
            if (o.collidesWith(playerBounds)) {
                crash()
                break
            }
        }

        // Score is distance (in "meters") plus a gem bonus.
        score = (distance / (w * 0.05f)).toInt() + gemCount * 10
    }

    private fun spawnPattern() {
        val spikeW = w * (0.085f + Random.nextFloat() * 0.04f)
        val spikeH = (floorY - ceilingY) * (0.18f + Random.nextFloat() * 0.16f)
        val spawnX = w + spikeW

        when (Random.nextInt(4)) {
            0 -> obstacles.add(Obstacle(spawnX, spikeW, spikeH, true, floorY, ceilingY))
            1 -> obstacles.add(Obstacle(spawnX, spikeW, spikeH, false, floorY, ceilingY))
            2 -> {
                // Floor spike with a reward gem near the ceiling lane.
                obstacles.add(Obstacle(spawnX, spikeW, spikeH, true, floorY, ceilingY))
                gems.add(Gem(spawnX + spikeW * 0.5f, ceilingY + player.radius * 2.2f, player.radius * 0.7f))
            }
            else -> {
                // Ceiling spike with a reward gem near the floor lane.
                obstacles.add(Obstacle(spawnX, spikeW, spikeH, false, floorY, ceilingY))
                gems.add(Gem(spawnX + spikeW * 0.5f, floorY - player.radius * 2.2f, player.radius * 0.7f))
            }
        }
    }

    private fun crash() {
        sound.crash()
        particles.burst(player.x, player.y, currentPlayerColor(), 40, player.radius * 1.6f)
        newRecord = prefs.submitScore(score)
        bestScore = prefs.bestScore
        state = State.GAME_OVER
        gameOverAt = System.currentTimeMillis()
    }

    // ----------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------

    private fun currentPlayerColor(): Int =
        if (player.gravityDir > 0) Color.parseColor("#22E0C8") else Color.parseColor("#FF4D7A")

    private fun drawGame(canvas: Canvas) {
        drawBackground(canvas)

        // Play-field bars (floor + ceiling).
        barPaint.color = Color.parseColor("#1A2230")
        canvas.drawRect(0f, 0f, w, ceilingY, barPaint)
        canvas.drawRect(0f, floorY, w, h, barPaint)
        barPaint.color = Color.parseColor("#2C3A52")
        val edge = h * 0.006f
        canvas.drawRect(0f, ceilingY - edge, w, ceilingY, barPaint)
        canvas.drawRect(0f, floorY, w, floorY + edge, barPaint)

        for (o in obstacles) o.draw(canvas, Color.parseColor("#FF6B81"))
        for (g in gems) g.draw(canvas)

        if (this::player.isInitialized && state != State.GAME_OVER) {
            player.draw(canvas, currentPlayerColor())
        }
        particles.draw(canvas)

        when (state) {
            State.READY -> drawReady(canvas)
            State.PLAYING -> drawHud(canvas)
            State.PAUSED -> { drawHud(canvas); drawPaused(canvas) }
            State.GAME_OVER -> drawGameOver(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        bgPaint.color = Color.parseColor("#0E1116")
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Subtle scrolling vertical grid for a sense of speed.
        gridPaint.color = 0x14FFFFFF
        gridPaint.strokeWidth = max(1f, w * 0.0025f)
        val step = w * 0.12f
        val offset = (bgPhase * (if (state == State.PLAYING) speed else baseSpeed()) * 0.4f) % step
        var x = -offset
        while (x < w) {
            canvas.drawLine(x, ceilingY, x, floorY, gridPaint)
            x += step
        }
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.06f
        canvas.drawText(score.toString(), w / 2f, ceilingY + h * 0.08f, textPaint)
        textPaint.textSize = h * 0.022f
        textPaint.color = 0x88FFFFFF.toInt()
        canvas.drawText("BEST $bestScore", w / 2f, ceilingY + h * 0.11f, textPaint)
    }

    private fun drawReady(canvas: Canvas) {
        textPaint.color = Color.parseColor("#22E0C8")
        textPaint.textSize = h * 0.085f
        canvas.drawText("FLIPSIDE", w / 2f, h * 0.40f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.030f
        canvas.drawText("TAP to flip gravity", w / 2f, h * 0.50f, textPaint)
        canvas.drawText("Dodge spikes • grab gems", w / 2f, h * 0.545f, textPaint)

        textPaint.color = 0xCCFFFFFF.toInt()
        textPaint.textSize = h * 0.040f
        val pulse = (0.5f + 0.5f * kotlin.math.sin(bgPhase * 0.08f))
        textPaint.alpha = (120 + 135 * pulse).toInt()
        canvas.drawText("TAP TO START", w / 2f, h * 0.66f, textPaint)
        textPaint.alpha = 255

        textPaint.color = 0x88FFFFFF.toInt()
        textPaint.textSize = h * 0.024f
        canvas.drawText("BEST $bestScore", w / 2f, h * 0.74f, textPaint)
    }

    private fun drawPaused(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.06f
        canvas.drawText("PAUSED", w / 2f, h * 0.46f, textPaint)
        textPaint.textSize = h * 0.030f
        canvas.drawText("Tap to resume", w / 2f, h * 0.54f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)

        textPaint.color = Color.parseColor("#FF4D7A")
        textPaint.textSize = h * 0.07f
        canvas.drawText("GAME OVER", w / 2f, h * 0.36f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.10f
        canvas.drawText(score.toString(), w / 2f, h * 0.49f, textPaint)

        textPaint.textSize = h * 0.030f
        if (newRecord) {
            textPaint.color = Color.parseColor("#FFD25A")
            canvas.drawText("NEW BEST!", w / 2f, h * 0.55f, textPaint)
        } else {
            textPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText("BEST $bestScore", w / 2f, h * 0.55f, textPaint)
        }

        textPaint.color = 0xCCFFFFFF.toInt()
        textPaint.textSize = h * 0.038f
        val pulse = (0.5f + 0.5f * kotlin.math.sin(bgPhase * 0.08f))
        textPaint.alpha = (120 + 135 * pulse).toInt()
        canvas.drawText("TAP TO RETRY", w / 2f, h * 0.66f, textPaint)
        textPaint.alpha = 255
    }

    // ----------------------------------------------------------------------
    // Render thread
    // ----------------------------------------------------------------------

    private inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        @Volatile
        var running = false
        private val targetFrameMs = 1000L / 60L

        override fun run() {
            while (running) {
                val frameStart = System.currentTimeMillis()
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null && w > 0f) {
                        synchronized(surfaceHolder) {
                            update()
                            drawGame(canvas)
                        }
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Throwable) {
                        }
                    }
                }
                val elapsed = System.currentTimeMillis() - frameStart
                val sleep = targetFrameMs - elapsed
                if (sleep > 0) {
                    try {
                        sleep(sleep)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }
}
