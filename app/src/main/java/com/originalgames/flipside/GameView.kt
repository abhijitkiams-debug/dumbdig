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
 * Two modes share the same loop: ENDLESS (random layout) and DAILY CHALLENGE (a
 * date-seeded layout that is identical for everyone, so scores are comparable
 * and shareable). Collected gems are a lifetime currency that unlocks orb skins.
 *
 * Everything drawn here is generated from primitive shapes and text — there are
 * no imported images, fonts, or sounds anywhere in the project.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { READY, PLAYING, PAUSED, GAME_OVER }

    private val prefs = Prefs(context)
    private val sound = SoundManager(prefs.soundEnabled)
    private val haptics = Haptics(context, prefs.hapticsEnabled)
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

    // --- Meta-progression / modes ---
    private var lifetimeGems = prefs.lifetimeGems
    private var selectedSkin =
        if (Skins.isUnlocked(Skins.clampIndex(prefs.selectedSkin), prefs.lifetimeGems))
            Skins.clampIndex(prefs.selectedSkin) else 0
    private var skin = Skins.all[selectedSkin]
    private var daily = false
    private var rng: Random = Random.Default
    private val dateLabel = todayLabel()
    private var dailyStreak = 0

    // Per-run tick clock + flip log, used to record and replay the daily ghost.
    @Volatile private var tickCount = 0
    private val recordedFlips = ArrayList<Int>()

    // Daily ghost: your best run of the day re-simulated as a translucent racer.
    private var ghostPlayer: Player? = null
    private var ghostFlips: IntArray = IntArray(0)
    private var ghostIndex = 0
    private var ghostSurvived = 0
    private var ghostScore = 0
    private var ghostShowing = false

    // --- UI hit regions (computed in surfaceChanged) ---
    private val rectMode = RectF()
    private val rectSkinLeft = RectF()
    private val rectSkinRight = RectF()
    private val rectPlay = RectF()
    private val rectRetry = RectF()
    private val rectShare = RectF()
    private val rectFx = RectF()

    // --- Paints (reused; never allocate in the loop) ---
    private val bgPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        dailyStreak = prefs.currentStreak(dateLabel, yesterdayLabel())
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
        layoutUi()
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

    /** Toggles sound + haptics together as one "FX" switch. */
    private fun toggleFx() {
        val on = !sound.enabled
        sound.enabled = on
        haptics.enabled = on
        prefs.soundEnabled = on
        prefs.hapticsEnabled = on
        if (on) { sound.flip(); haptics.light() }
    }

    private fun layoutUi() {
        val cx = w / 2f
        val bw = w * 0.5f
        val bh = h * 0.085f
        val mw = w * 0.64f
        val mh = h * 0.06f
        rectMode.set(cx - mw / 2f, h * 0.255f, cx + mw / 2f, h * 0.255f + mh)

        val cs = w * 0.14f
        val oy = h * 0.45f
        rectSkinLeft.set(w * 0.16f - cs, oy - cs, w * 0.16f + cs, oy + cs)
        rectSkinRight.set(w * 0.84f - cs, oy - cs, w * 0.84f + cs, oy + cs)

        rectPlay.set(cx - bw / 2f, h * 0.66f, cx + bw / 2f, h * 0.66f + bh)
        rectRetry.set(cx - bw / 2f, h * 0.60f, cx + bw / 2f, h * 0.60f + bh)
        rectShare.set(cx - bw / 2f, h * 0.72f, cx + bw / 2f, h * 0.72f + bh)

        val fw = w * 0.24f
        val fh = h * 0.045f
        rectFx.set(w - fw - w * 0.04f, h * 0.045f, w - w * 0.04f, h * 0.045f + fh)
    }

    // ----------------------------------------------------------------------
    // Input
    // ----------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x
        val y = event.y
        when (state) {
            State.READY -> handleReadyTap(x, y)
            State.PLAYING -> {
                // Synchronise with the render thread so the flip log stays consistent.
                synchronized(holder) {
                    player.flip()
                    recordedFlips.add(tickCount)
                }
                sound.flip()
                haptics.light()
            }
            State.PAUSED -> state = State.PLAYING
            State.GAME_OVER -> handleGameOverTap(x, y)
        }
        return true
    }

    private fun handleReadyTap(x: Float, y: Float) {
        when {
            rectFx.contains(x, y) -> toggleFx()
            rectMode.contains(x, y) -> {
                daily = !daily
                sound.flip()
                resetWorld()
            }
            rectSkinLeft.contains(x, y) -> cycleSkin(-1)
            rectSkinRight.contains(x, y) -> cycleSkin(+1)
            rectPlay.contains(x, y) -> startRun()
        }
    }

    private fun handleGameOverTap(x: Float, y: Float) {
        if (rectShare.contains(x, y)) {
            ShareCard.share(context, score, gemCount, daily, dateLabel, skin, dailyStreak)
            return
        }
        // Anywhere else (after a short lockout) restarts.
        if (System.currentTimeMillis() - gameOverAt > 600) {
            state = State.READY
            resetWorld()
        }
    }

    private fun cycleSkin(dir: Int) {
        // Step to the next *unlocked* skin in the chosen direction.
        var i = selectedSkin
        do {
            i = Skins.clampIndex(i + dir)
        } while (!Skins.isUnlocked(i, lifetimeGems) && i != selectedSkin)
        selectedSkin = i
        skin = Skins.all[i]
        prefs.selectedSkin = i
        sound.flip()
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
        tickCount = 0
        recordedFlips.clear()
        // Daily mode uses a date-seeded RNG so the layout is identical for all
        // players that day; endless mode is freshly random each run.
        rng = if (daily) Random(dailySeed()) else Random(Random.nextLong())
        loadGhost()
    }

    /** Loads today's saved ghost (daily only) and primes a shadow Player to replay it. */
    private fun loadGhost() {
        ghostPlayer = null
        ghostFlips = IntArray(0)
        ghostIndex = 0
        ghostSurvived = 0
        ghostScore = 0
        ghostShowing = false
        if (!daily) return
        val data = prefs.ghostFor(dateLabel) ?: return
        ghostScore = prefs.ghostScoreFor(dateLabel)
        val parts = data.split(";")
        ghostSurvived = parts.getOrNull(0)?.toIntOrNull() ?: 0
        ghostFlips = parts.getOrNull(1)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toIntArray()
            ?: IntArray(0)
        if (ghostSurvived > 0) {
            ghostPlayer = Player(player.x, floorY - player.radius, player.radius, floorY, ceilingY)
            ghostShowing = true
        }
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
        tickCount++

        // Difficulty curve: speed creeps up with distance, then plateaus.
        speed = min(baseSpeed() * 2.4f, baseSpeed() + distance * 0.0000016f * w)
        distance += speed
        player.update()
        updateGhost()

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
                    lifetimeGems++
                    prefs.addLifetimeGems(1)
                    particles.burst(g.x, g.y, Color.parseColor("#FFD25A"), 16, player.radius * 0.9f)
                    sound.gem()
                    haptics.pop()
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

    private fun updateGhost() {
        if (!daily || !ghostShowing) return
        val g = ghostPlayer ?: return
        // Replay recorded flips as the clock reaches each one, then step physics.
        while (ghostIndex < ghostFlips.size && ghostFlips[ghostIndex] <= tickCount) {
            g.flip()
            ghostIndex++
        }
        if (tickCount <= ghostSurvived) g.update() else ghostShowing = false
    }

    private fun spawnPattern() {
        val spikeW = w * (0.085f + rng.nextFloat() * 0.04f)
        val spikeH = (floorY - ceilingY) * (0.18f + rng.nextFloat() * 0.16f)
        val spawnX = w + spikeW

        when (rng.nextInt(4)) {
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
        haptics.crash()
        particles.burst(player.x, player.y, currentPlayerColor(), 40, player.radius * 1.6f)
        // Only endless runs count toward the saved best; daily is for comparing.
        newRecord = if (!daily) prefs.submitScore(score) else false
        bestScore = prefs.bestScore
        if (daily) {
            dailyStreak = prefs.registerDailyCompletion(dateLabel, yesterdayLabel())
            // Persist this run as the ghost if it's the day's best.
            val data = tickCount.toString() + ";" + recordedFlips.joinToString(",")
            prefs.saveGhostIfBetter(dateLabel, score, data)
        }
        state = State.GAME_OVER
        gameOverAt = System.currentTimeMillis()
    }

    // ----------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------

    private fun currentPlayerColor(): Int =
        if (player.gravityDir > 0) skin.down else skin.up

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

        if (state == State.PLAYING && daily && ghostShowing) {
            ghostPlayer?.let { drawGhost(canvas, it) }
        }

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
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.06f
        canvas.drawText(score.toString(), w / 2f, ceilingY + h * 0.08f, textPaint)
        textPaint.textSize = h * 0.022f
        textPaint.color = 0x88FFFFFF.toInt()
        val label = if (daily) "DAILY  •  BEST $bestScore" else "BEST $bestScore"
        canvas.drawText(label, w / 2f, ceilingY + h * 0.11f, textPaint)
    }

    private fun drawGhost(canvas: Canvas, g: Player) {
        val r = player.radius
        orbPaint.style = Paint.Style.FILL
        orbPaint.color = 0x30FFFFFF
        canvas.drawCircle(g.x, g.y, r * 1.25f, orbPaint)
        orbPaint.style = Paint.Style.STROKE
        orbPaint.strokeWidth = r * 0.16f
        orbPaint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(g.x, g.y, r, orbPaint)
        orbPaint.style = Paint.Style.FILL
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, fill: Int, txt: Int, size: Float) {
        barPaint.color = fill
        val radius = r.height() / 2f
        canvas.drawRoundRect(r, radius, radius, barPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = txt
        textPaint.textSize = size
        canvas.drawText(label, r.centerX(), r.centerY() + size * 0.35f, textPaint)
    }

    private fun drawReady(canvas: Canvas) {
        val cx = w / 2f
        textPaint.textAlign = Paint.Align.CENTER

        textPaint.color = Color.parseColor("#22E0C8")
        textPaint.textSize = h * 0.085f
        canvas.drawText("FLIPSIDE", cx, h * 0.20f, textPaint)

        // Mode toggle pill.
        val modeFill = if (daily) Color.parseColor("#3A2A12") else Color.parseColor("#1A2230")
        val modeTxt = if (daily) Color.parseColor("#FFD25A") else 0xCCFFFFFF.toInt()
        val modeLabel = if (daily) "DAILY  •  $dateLabel" else "ENDLESS"
        drawButton(canvas, rectMode, modeLabel, modeFill, modeTxt, h * 0.028f)
        textPaint.textSize = h * 0.020f
        if (daily) {
            textPaint.color = 0x99FFFFFF.toInt()
            val info = if (ghostScore > 0)
                "streak $dailyStreak   •   race your ghost (best $ghostScore)"
            else
                "streak $dailyStreak   •   set today's ghost!"
            canvas.drawText(info, cx, rectMode.bottom + h * 0.035f, textPaint)
        } else {
            textPaint.color = 0x66FFFFFF.toInt()
            canvas.drawText("tap to switch mode", cx, rectMode.bottom + h * 0.035f, textPaint)
        }

        // FX (sound + haptics) toggle, top-right.
        val fxOn = sound.enabled
        drawButton(
            canvas, rectFx, if (fxOn) "FX: ON" else "FX: OFF",
            if (fxOn) Color.parseColor("#1A2230") else Color.parseColor("#2A1A1A"),
            if (fxOn) 0xCCFFFFFF.toInt() else 0x77FFFFFF.toInt(), h * 0.022f
        )

        // Skin preview orb with chevrons.
        val oy = h * 0.45f
        val r = w * 0.07f
        orbPaint.color = (0x55 shl 24) or (skin.down and 0x00FFFFFF)
        canvas.drawCircle(cx, oy, r * 1.6f, orbPaint)
        orbPaint.color = skin.down
        canvas.drawCircle(cx, oy, r, orbPaint)
        orbPaint.color = Color.WHITE
        canvas.drawCircle(cx, oy + r * 0.35f, r * 0.32f, orbPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.06f
        canvas.drawText("‹", rectSkinLeft.centerX(), oy + h * 0.022f, textPaint)
        canvas.drawText("›", rectSkinRight.centerX(), oy + h * 0.022f, textPaint)

        // Skin name + unlock progress.
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.034f
        canvas.drawText(skin.name, cx, h * 0.56f, textPaint)

        val nextLocked = Skins.all.firstOrNull { it.cost > lifetimeGems }
        textPaint.textSize = h * 0.022f
        if (nextLocked != null) {
            textPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText(
                "next: ${nextLocked.name} @ ${nextLocked.cost} gems  (you: $lifetimeGems)",
                cx, h * 0.595f, textPaint
            )
        } else {
            textPaint.color = Color.parseColor("#B8FF3B")
            canvas.drawText("all skins unlocked  •  gems: $lifetimeGems", cx, h * 0.595f, textPaint)
        }

        // Play button (pulsing).
        val pulse = 0.5f + 0.5f * kotlin.math.sin(bgPhase * 0.08f)
        val playFill = Color.parseColor("#22E0C8")
        barPaint.color = playFill
        barPaint.alpha = (200 + 55 * pulse).toInt()
        val pr = rectPlay.height() / 2f
        canvas.drawRoundRect(rectPlay, pr, pr, barPaint)
        barPaint.alpha = 255
        textPaint.color = Color.parseColor("#06231F")
        textPaint.textSize = h * 0.040f
        canvas.drawText("PLAY", cx, rectPlay.centerY() + h * 0.014f, textPaint)

        textPaint.color = 0x88FFFFFF.toInt()
        textPaint.textSize = h * 0.024f
        canvas.drawText("BEST $bestScore", cx, h * 0.80f, textPaint)
    }

    private fun drawPaused(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.06f
        canvas.drawText("PAUSED", w / 2f, h * 0.46f, textPaint)
        textPaint.textSize = h * 0.030f
        canvas.drawText("Tap to resume", w / 2f, h * 0.54f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        val cx = w / 2f
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.textAlign = Paint.Align.CENTER

        textPaint.color = Color.parseColor("#FF4D7A")
        textPaint.textSize = h * 0.07f
        canvas.drawText("GAME OVER", cx, h * 0.30f, textPaint)

        textPaint.color = if (daily) Color.parseColor("#FFD25A") else 0x88FFFFFF.toInt()
        textPaint.textSize = h * 0.026f
        canvas.drawText(if (daily) "DAILY  •  $dateLabel" else "ENDLESS", cx, h * 0.345f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.10f
        canvas.drawText(score.toString(), cx, h * 0.45f, textPaint)

        textPaint.textSize = h * 0.028f
        if (newRecord) {
            textPaint.color = Color.parseColor("#FFD25A")
            canvas.drawText("NEW BEST!  •  $gemCount gems", cx, h * 0.50f, textPaint)
        } else {
            textPaint.color = 0x99FFFFFF.toInt()
            val best = if (daily) "$gemCount gems collected" else "BEST $bestScore  •  $gemCount gems"
            canvas.drawText(best, cx, h * 0.50f, textPaint)
        }

        if (daily) {
            textPaint.color = Color.parseColor("#FFD25A")
            textPaint.textSize = h * 0.024f
            val tag = if (ghostScore in 1..score) "DAY STREAK $dailyStreak  •  beat your ghost!"
            else "DAY STREAK $dailyStreak"
            canvas.drawText(tag, cx, h * 0.545f, textPaint)
        }

        drawButton(canvas, rectRetry, "RETRY", Color.parseColor("#22E0C8"), Color.parseColor("#06231F"), h * 0.038f)
        drawButton(canvas, rectShare, "SHARE", Color.parseColor("#2C3A52"), Color.WHITE, h * 0.036f)
    }

    // ----------------------------------------------------------------------
    // Date helpers for the daily challenge
    // ----------------------------------------------------------------------

    private fun todayLabel(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun yesterdayLabel(): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }

    private fun dailySeed(): Long =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date()).toLong()

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
