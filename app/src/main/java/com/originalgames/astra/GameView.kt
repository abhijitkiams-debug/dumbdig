package com.originalgames.astra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game lives here: a [SurfaceView] driven by its own 60 fps render
 * thread.
 *
 * ASTRA — RAM vs RAVAN is a one-thumb boss-survival archer. Ram stands at the
 * bottom and auto-looses arrows at the ten-headed demon-king Ravan above; the
 * player drags to dodge the storm of aayudha (arrows, maces, tridents, discs,
 * fire-bolts) Ravan rains down. Filling the astra meter unleashes a rotating
 * arsenal of divine astras. Each severed head offers a roguelite boon, so every
 * run builds its own bow.
 *
 * Two modes share the loop: ENDLESS and a date-seeded DAILY battle that is the
 * same for everyone (so scores are directly comparable and shareable).
 *
 * Everything is generated from primitive shapes and synthesized tones — no
 * imported images, fonts, or audio anywhere in the project.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { READY, PLAYING, CHOOSING_BOON, PAUSED, GAME_OVER }

    private val prefs = Prefs(context)
    private val sound = SoundManager(prefs.soundEnabled)
    private val haptics = Haptics(context, prefs.hapticsEnabled)
    private val particles = ParticleSystem()

    private var thread: GameThread? = null
    private var state = State.READY

    // --- Geometry (set in surfaceChanged) ---
    private var w = 0f
    private var h = 0f
    private lateinit var ram: Ram
    private lateinit var ravan: Ravan

    // --- World state ---
    private val arrows = ArrayList<Arrow>()
    private val aayudhas = ArrayList<Aayudha>()
    private val astras = ArrayList<ActiveAstra>()
    private val loadout = Loadout()

    private var fireCooldown = 0
    private var attackTimer = 0
    private var astraMeter = 0f
    private var armedAstra = AstraType.AGNEYA
    private var headsSevered = 0
    private var clashCount = 0
    private var shake = 0f
    private var rage = false
    private var damageDealt = 0f
    private var survivalTicks = 0
    private var score = 0
    private var bestScore = prefs.bestScore
    private var newRecord = false
    private var gameOverAt = 0L
    private var bgPhase = 0f
    private var hurtFlash = 0f
    private var victoryBanner = 0

    // Global pace multiplier: <1 slows the whole battle down for readability.
    private val pace = 0.58f

    // --- Aiming (Pocket-Tanks-style manual shots) ---
    private enum class Touch { NONE, MOVE, AIM }
    private var touchMode = Touch.NONE
    private var aimX = 0f
    private var aimY = 0f
    private var manualCooldown = 0

    // --- Modes ---
    private var daily = false
    private var rng: Random = Random.Default
    private val dateLabel = todayLabel()
    private var dailyStreak = 0

    // --- Boon offer ---
    private var offered: List<Boon> = emptyList()

    // --- UI hit regions (computed in surfaceChanged) ---
    private val rectMode = RectF()
    private val rectPlay = RectF()
    private val rectRetry = RectF()
    private val rectShare = RectF()
    private val rectFx = RectF()
    private val rectAstra = RectF()
    private val boonRects = arrayOf(RectF(), RectF(), RectF())

    // --- Paints (reused; never allocate in the loop) ---
    private val bgPaint = Paint()
    private val skyPaint = Paint()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD
        )
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = 0xC0000000.toInt() }
    private val path = Path()
    private val tmpZone = RectF()
    private val tmpBounds = RectF()

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
        val ramR = min(w, h) * 0.085f
        val ramY = h * 0.84f
        ram = Ram(w / 2f, ramY, ramR, w * 0.10f, w * 0.90f)
        ravan = Ravan(w, h)
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF1A0E22.toInt(), 0xFF2A1020.toInt(), 0xFF120A12.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        layoutUi()
        resetWorld()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.let {
            it.running = false
            var retry = true
            while (retry) {
                try { it.join(); retry = false } catch (_: InterruptedException) {}
            }
        }
        thread = null
    }

    fun onActivityPause() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    private fun toggleFx() {
        val on = !sound.enabled
        sound.enabled = on
        haptics.enabled = on
        prefs.soundEnabled = on
        prefs.hapticsEnabled = on
        if (on) { sound.confirm(); haptics.light() }
    }

    private fun layoutUi() {
        val cx = w / 2f
        val bw = w * 0.56f
        val bh = h * 0.075f
        val mw = w * 0.64f
        val mh = h * 0.05f
        rectMode.set(cx - mw / 2f, h * 0.30f, cx + mw / 2f, h * 0.30f + mh)
        rectPlay.set(cx - bw / 2f, h * 0.66f, cx + bw / 2f, h * 0.66f + bh)
        rectRetry.set(cx - bw / 2f, h * 0.62f, cx + bw / 2f, h * 0.62f + bh)
        rectShare.set(cx - bw / 2f, h * 0.71f, cx + bw / 2f, h * 0.71f + bh)
        val fw = w * 0.26f; val fh = h * 0.04f
        rectFx.set(w - fw - w * 0.04f, h * 0.035f, w - w * 0.04f, h * 0.035f + fh)
        // Floating astra button: small, kept clear of Ram's dodging lane so it
        // never blocks the action.
        val ar = w * 0.082f
        val acx = w - ar - w * 0.045f; val acy = h * 0.70f
        rectAstra.set(acx - ar, acy - ar, acx + ar, acy + ar)
        val cw = w * 0.74f; val ch = h * 0.11f
        for (i in 0..2) {
            val top = h * 0.40f + i * (ch + h * 0.025f)
            boonRects[i].set(cx - cw / 2f, top, cx + cw / 2f, top + ch)
        }
    }

    // ----------------------------------------------------------------------
    // Input
    // ----------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.x
        val y = event.y
        // Hold the render lock so input never races the world update/draw.
        synchronized(holder) {
            when (state) {
                State.READY -> if (action == MotionEvent.ACTION_DOWN) handleReadyTap(x, y)
                State.PLAYING -> handlePlayTouch(action, x, y)
                State.CHOOSING_BOON -> if (action == MotionEvent.ACTION_DOWN) handleBoonTap(x, y)
                State.PAUSED -> if (action == MotionEvent.ACTION_DOWN) state = State.PLAYING
                State.GAME_OVER -> if (action == MotionEvent.ACTION_DOWN) handleGameOverTap(x, y)
            }
        }
        return true
    }

    private fun handlePlayTouch(action: Int, x: Float, y: Float) {
        // Vertical control split: drag down low to MOVE Ram (dodge), drag up high
        // to AIM a charged shot (angle + power) and release to loose it. The
        // floating astra button fires the armed astra.
        val split = h * 0.62f
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                when {
                    rectAstra.contains(x, y) -> { if (astraMeter >= 1f) fireAstra(); touchMode = Touch.NONE }
                    y > split -> { touchMode = Touch.MOVE; ram.moveTo(x) }
                    else -> { touchMode = Touch.AIM; aimX = x; aimY = y }
                }
            }
            MotionEvent.ACTION_MOVE -> when (touchMode) {
                Touch.MOVE -> ram.moveTo(x)
                Touch.AIM -> { aimX = x; aimY = y }
                Touch.NONE -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchMode == Touch.AIM) fireAimedArrow(aimX, aimY)
                touchMode = Touch.NONE
            }
        }
    }

    /** Fires a powerful, player-aimed arrow toward [tx],[ty]; power scales with drag length. */
    private fun fireAimedArrow(tx: Float, ty: Float) {
        if (manualCooldown > 0) return
        val dx = tx - ram.x; val dy = ty - (ram.y - ram.radius)
        val dist = kotlin.math.hypot(dx, dy)
        if (dist < ram.radius * 0.6f) return          // ignore taps that aren't a real aim
        val dirX = dx / dist; val dirY = dy / dist
        if (dirY > -0.15f) return                     // must aim generally upward, toward Ravan
        val power = (dist / (h * 0.42f)).coerceIn(0.35f, 1f)
        val speed = (h * 0.016f + h * 0.012f * power) * pace * 1.9f
        val dmg = loadout.arrowDamage * (1.6f + power * 1.9f)
        arrows.add(
            Arrow(
                ram.x, ram.y - ram.radius, dirX * speed, dirY * speed,
                damage = dmg, length = h * 0.024f,
                color = 0xFFFFF3C0.toInt(), pierce = loadout.pierce
            )
        )
        manualCooldown = 10
        sound.shoot(); haptics.light()
    }

    private fun handleReadyTap(x: Float, y: Float) {
        when {
            rectFx.contains(x, y) -> toggleFx()
            rectMode.contains(x, y) -> { daily = !daily; sound.confirm(); resetWorld() }
            rectPlay.contains(x, y) -> startRun()
        }
    }

    private fun handleBoonTap(x: Float, y: Float) {
        for (i in offered.indices) {
            if (boonRects[i].contains(x, y)) {
                synchronized(holder) {
                    offered[i].apply(loadout, ram)
                    ram.invuln = 50
                    attackTimer = (attackTimer).coerceAtLeast(40)
                    state = State.PLAYING
                }
                sound.confirm(); haptics.pop()
                return
            }
        }
    }

    private fun handleGameOverTap(x: Float, y: Float) {
        if (rectShare.contains(x, y)) {
            ShareCard.share(context, score, headsSevered, clashCount, daily, dateLabel, dailyStreak, rage)
            return
        }
        if (System.currentTimeMillis() - gameOverAt > 500) {
            state = State.READY
            resetWorld()
        }
    }

    // ----------------------------------------------------------------------
    // Game flow
    // ----------------------------------------------------------------------

    private fun resetWorld() {
        if (!this::ram.isInitialized) return
        arrows.clear(); aayudhas.clear(); astras.clear(); particles.clear()
        ram.reset(w / 2f)
        ravan.reset()
        loadout.reset()
        fireCooldown = 0
        attackTimer = 120
        touchMode = Touch.NONE
        manualCooldown = 0
        astraMeter = 0f
        armedAstra = AstraType.AGNEYA
        headsSevered = 0
        clashCount = 0
        shake = 0f
        rage = false
        damageDealt = 0f
        survivalTicks = 0
        score = 0
        newRecord = false
        hurtFlash = 0f
        victoryBanner = 0
        rng = if (daily) Random(dailySeed()) else Random(Random.nextLong())
    }

    private fun startRun() {
        resetWorld()
        state = State.PLAYING
        sound.confirm()
    }

    private fun update() {
        bgPhase += 1f
        particles.update()
        var i = astras.size - 1
        while (i >= 0) { astras[i].update(); if (!astras[i].alive) astras.removeAt(i); i-- }
        if (hurtFlash > 0f) hurtFlash *= 0.86f
        if (shake > 0.2f) shake *= 0.80f else shake = 0f
        if (victoryBanner > 0) victoryBanner--

        if (state != State.PLAYING) return
        survivalTicks++
        if (manualCooldown > 0) manualCooldown--
        ram.update()
        ravan.update()

        // Auto-fire volley.
        if (--fireCooldown <= 0) { fireVolley(); fireCooldown = loadout.fireInterval }

        // Enemy attack scheduling.
        if (--attackTimer <= 0) { spawnAttackWave(); attackTimer = nextAttackGap() }

        ravan.strikeZone(tmpZone)
        var severedThisFrame = false

        // Arrows.
        run {
            var k = arrows.size - 1
            while (k >= 0) {
                val a = arrows[k]
                // Homing arrows (Nagastra) seek the nearest aayudha, else Ravan.
                if (a.homing) {
                    val t = nearestAayudha(a.x, a.y)
                    if (t != null) a.update(t.x, t.y) else a.update(ravan.activeHeadX(), ravan.activeHeadY())
                } else a.update(a.x, a.y)

                // Strike incoming destructible aayudha.
                var consumed = false
                var j = aayudhas.size - 1
                while (j >= 0) {
                    val ay = aayudhas[j]
                    if ((ay.destructible || a.homing) && ay.overlaps(a.x, a.y, a.length)) {
                        if (ay.type == Aayudha.Type.RBAAN) {
                            // A true arrow-vs-arrow clash — extra juice + a tally.
                            clashCount++
                            shake = w * 0.012f
                            particles.burst(ay.x, ay.y, Ram.GOLD, 12, w * 0.03f, gravity = 0.1f)
                            particles.burst(ay.x, ay.y, Aayudha.C_RBAAN, 12, w * 0.03f, gravity = 0.1f)
                            if (Random.nextFloat() < 0.5f) sound.hit()
                            haptics.light()
                        } else {
                            particles.burst(ay.x, ay.y, colorOf(ay.type), 10, w * 0.02f)
                        }
                        aayudhas.removeAt(j)
                        if (!a.pierce) { consumed = true; break }
                    }
                    j--
                }
                if (consumed) { arrows.removeAt(k); k--; continue }

                // Strike Ravan when the arrow reaches the head band.
                if (a.alive && a.y <= tmpZone.bottom && a.vy < 0f) {
                    var dmg = a.damage
                    // Use the non-seeded RNG for crit/SFX so the daily seed only
                    // ever drives the (identical-for-everyone) attack layout.
                    if (loadout.critChance > 0f && Random.nextFloat() < loadout.critChance) dmg *= 2f
                    if (damageRavan(dmg)) severedThisFrame = true
                    if (Random.nextFloat() < 0.25f) sound.hit()
                    particles.burst(a.x, tmpZone.bottom, 0xFFFFD24A.toInt(), 6, w * 0.015f)
                    if (!a.pierce) { arrows.removeAt(k); k--; continue }
                    a.alive = false // pierce continues flying but won't re-damage Ravan
                }
                if (a.isOffScreen(h)) { arrows.removeAt(k) }
                k--
            }
        }

        // Aayudha movement + collision with Ram.
        ram.bounds(tmpBounds)
        val rcx = tmpBounds.centerX(); val rcy = tmpBounds.centerY(); val rr = tmpBounds.width() / 2f
        run {
            var k = aayudhas.size - 1
            while (k >= 0) {
                val ay = aayudhas[k]
                ay.update(ram.x, ram.y, h * 0.0006f)
                if (ay.overlaps(rcx, rcy, rr)) {
                    if (ram.takeHit()) {
                        hurtFlash = 1f
                        particles.burst(ram.x, ram.y, Ram.SAFFRON, 26, w * 0.03f)
                        sound.hurt(); haptics.crash()
                        aayudhas.removeAt(k)
                        if (ram.lives <= 0) { gameOver(); return }
                    }
                } else if (ay.isOffScreen(w, h)) {
                    aayudhas.removeAt(k)
                }
                k--
            }
        }

        // Astra meter passive trickle + score.
        astraMeter = (astraMeter + 0.0006f * loadout.astraChargeMult).coerceAtMost(1f)
        score = headsSevered * 250 + damageDealt.toInt() + survivalTicks / 3

        if (severedThisFrame && state == State.PLAYING) openBoon()
    }

    /** Applies [amount] damage across Ravan's heads; returns true if any head fell. */
    private fun damageRavan(amount: Float): Boolean {
        damageDealt += amount
        astraMeter = (astraMeter + amount * 0.012f * loadout.astraChargeMult).coerceAtMost(1f)
        var any = false
        var remaining = amount
        // A single hit can topple at most a few heads (prevents runaway chains).
        var guard = 4
        while (remaining > 0f && ravan.headsAlive > 0 && guard-- > 0) {
            val hp = ravan.activeHeadHp
            val hx = ravan.activeHeadX(); val hy = ravan.activeHeadY()
            val severed = ravan.damage(remaining)
            if (severed) {
                any = true
                headsSevered++
                particles.burst(hx, hy, 0xFFFF4D5E.toInt(), 34, w * 0.05f)
                sound.sever(); haptics.heavy()
                if (ravan.headsAlive == 0) onRavanCleared()
                remaining -= hp
            } else remaining = 0f
        }
        return any
    }

    private fun onRavanCleared() {
        if (!rage) {
            rage = true
            victoryBanner = 150
            sound.victory()
        }
        ravan.regrow()
        particles.burst(w / 2f, h * 0.18f, Ram.GOLD, 60, w * 0.06f)
    }

    private fun openBoon() {
        // Boons draw from the non-seeded RNG so they never shift the daily course.
        offered = Boon.offer(Random.Default)
        state = State.CHOOSING_BOON
    }

    private fun fireVolley() {
        // Auto-fire is the steady baseline; aimed shots are the heavy hitters.
        sound.shoot()
        val n = loadout.arrowsPerShot
        val speed = h * 0.018f * pace * 1.7f
        val startY = ram.y - ram.radius * 1.2f
        val total = loadout.spread
        for (idx in 0 until n) {
            val frac = if (n == 1) 0f else idx / (n - 1f) - 0.5f
            val ang = -Math.PI.toFloat() / 2f + frac * total
            arrows.add(
                Arrow(
                    x = ram.x, y = startY,
                    vx = cos(ang) * speed, vy = sin(ang) * speed,
                    damage = loadout.arrowDamage * 0.6f,
                    length = h * 0.02f,
                    color = Ram.GOLD,
                    pierce = loadout.pierce
                )
            )
        }
    }

    private fun fireAstra() {
        val type = armedAstra
        var severed = false
        synchronized(holder) {
            astras.add(ActiveAstra(type, ram.x, ram.y))
            when (type) {
                AstraType.AGNEYA -> {
                    burnAllAayudha(0xFFFF6A1E.toInt())
                    severed = damageRavan(ravan.activeHeadMaxHp * 0.8f + 10f)
                }
                AstraType.VAYAVYA -> {
                    burnAllAayudha(AstraType.VAYAVYA.color)
                    severed = damageRavan(ravan.activeHeadMaxHp * 0.4f + 5f)
                }
                AstraType.NAGA -> {
                    val speed = h * 0.02f * pace * 1.7f
                    for (s in 0 until 6) {
                        val ang = -Math.PI.toFloat() / 2f + (s - 2.5f) * 0.18f
                        arrows.add(
                            Arrow(
                                ram.x, ram.y - ram.radius, cos(ang) * speed, sin(ang) * speed,
                                damage = loadout.arrowDamage * 2.2f, length = h * 0.022f,
                                color = AstraType.NAGA.color, pierce = true, homing = true
                            )
                        )
                    }
                }
                AstraType.BRAHMA -> {
                    burnAllAayudha(Color.WHITE)
                    severed = damageRavan(ravan.activeHeadMaxHp * 2.5f + 20f)
                }
            }
            astraMeter = 0f
            armedAstra = type.next()
            if (severed && state == State.PLAYING) openBoon()
        }
        sound.astra(); haptics.heavy()
    }

    private fun burnAllAayudha(color: Int) {
        for (ay in aayudhas) particles.burst(ay.x, ay.y, color, 8, w * 0.02f)
        aayudhas.clear()
    }

    private fun nearestAayudha(x: Float, y: Float): Aayudha? {
        var best: Aayudha? = null
        var bd = Float.MAX_VALUE
        for (ay in aayudhas) {
            val d = (ay.x - x) * (ay.x - x) + (ay.y - y) * (ay.y - y)
            if (d < bd) { bd = d; best = ay }
        }
        return best
    }

    // ----------------------------------------------------------------------
    // Enemy attack patterns
    // ----------------------------------------------------------------------

    private fun difficulty(): Int = headsSevered + ravan.hpTier * 10

    private fun nextAttackGap(): Int {
        val d = difficulty()
        // Longer gaps + slower ramp so the battle is readable, not frantic.
        val base = (70 - d * 1.6f).coerceAtLeast(30f)
        val jitter = 0.85f + rng.nextFloat() * 0.5f
        return (base * jitter / pace).toInt().coerceAtLeast(24)
    }

    private fun spawnAttackWave() {
        val d = difficulty()
        val topY = h * 0.20f
        // Slower fall + gentler difficulty ramp (scaled by the global pace).
        val fall = (h * 0.006f + d * h * 0.00011f) * pace
        // Pattern pool widens with difficulty.
        val pool = ArrayList<Int>()
        pool.add(6); pool.add(6); pool.add(0)            // Ravan's aimed arrows (core duel) + baan rain
        pool.add(1)                                      // chakra
        if (d >= 1) pool.add(2)                          // gada
        if (d >= 2) pool.add(3)                          // trishul
        if (d >= 3) pool.add(4)                          // shakti homing
        if (d >= 6) pool.add(5)                          // storm
        when (pool[rng.nextInt(pool.size)]) {
            0 -> {
                val count = 2 + d / 4
                for (s in 0 until count.coerceAtMost(7)) {
                    val x = w * (0.12f + rng.nextFloat() * 0.76f)
                    spawn(Aayudha.Type.BAAN, x, topY, 0f, fall * 1.3f, w * 0.012f)
                }
            }
            1 -> {
                val count = 1 + d / 5
                for (s in 0 until count.coerceAtMost(3)) {
                    val x = w * (0.2f + rng.nextFloat() * 0.6f)
                    val drift = (rng.nextFloat() - 0.5f) * w * 0.004f
                    spawn(Aayudha.Type.CHAKRA, x, topY, drift, fall, w * 0.03f)
                }
            }
            2 -> {
                val x = ravan.activeHeadX().coerceIn(w * 0.15f, w * 0.85f)
                val toRam = (ram.x - x) * 0.012f
                spawn(Aayudha.Type.GADA, x, topY, toRam, fall * 0.5f, w * 0.045f)
            }
            3 -> {
                val x = w * (0.15f + rng.nextFloat() * 0.7f)
                spawn(Aayudha.Type.TRISHUL, x, topY, 0f, fall * 1.5f, w * 0.03f)
            }
            4 -> {
                val x = ravan.activeHeadX()
                val ang = Math.PI / 2 + (rng.nextFloat() - 0.5f) * 0.6f
                val sp = fall * 1.2f
                spawn(Aayudha.Type.SHAKTI, x, topY,
                    (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat(), w * 0.022f)
            }
            6 -> {
                // Ravan looses a fan of aimed arrows straight at Ram — these are
                // destructible, so Ram's auto-arrows clash with them in mid-air.
                val count = (1 + d / 5).coerceAtMost(4)
                val ox = ravan.activeHeadX(); val oy = ravan.activeHeadY() + h * 0.02f
                val sp = fall * 1.45f
                for (s in 0 until count) {
                    val baseAng = kotlin.math.atan2(ram.y - oy, ram.x - ox)
                    val spreadAng = baseAng + (s - (count - 1) / 2f) * 0.10f
                    spawn(Aayudha.Type.RBAAN, ox, oy,
                        cos(spreadAng) * sp, sin(spreadAng) * sp, w * 0.016f)
                }
            }
            else -> {
                for (s in 0 until 5) {
                    val x = w * (0.12f + rng.nextFloat() * 0.76f)
                    val t = if (rng.nextBoolean()) Aayudha.Type.BAAN else Aayudha.Type.CHAKRA
                    spawn(t, x, topY, (rng.nextFloat() - 0.5f) * w * 0.003f, fall * 1.2f,
                        if (t == Aayudha.Type.BAAN) w * 0.012f else w * 0.028f)
                }
                if (d >= 4) {
                    // Mix in an aimed arrow during the storm.
                    val ox = ravan.activeHeadX(); val oy = ravan.activeHeadY() + h * 0.02f
                    val ang = kotlin.math.atan2(ram.y - oy, ram.x - ox)
                    spawn(Aayudha.Type.RBAAN, ox, oy, cos(ang) * fall * 1.5f, sin(ang) * fall * 1.5f, w * 0.016f)
                }
            }
        }
    }

    private fun spawn(type: Aayudha.Type, x: Float, y: Float, vx: Float, vy: Float, size: Float) {
        if (aayudhas.size > 60) return
        aayudhas.add(Aayudha(type, x, y, vx, vy, size))
    }

    private fun colorOf(t: Aayudha.Type): Int = when (t) {
        Aayudha.Type.BAAN -> Aayudha.C_BAAN
        Aayudha.Type.CHAKRA -> Aayudha.C_CHAKRA
        Aayudha.Type.GADA -> Aayudha.C_GADA
        Aayudha.Type.TRISHUL -> Aayudha.C_TRISHUL
        Aayudha.Type.SHAKTI -> Aayudha.C_SHAKTI
        Aayudha.Type.RBAAN -> Aayudha.C_RBAAN
    }

    private fun gameOver() {
        sound.hurt(); haptics.crash()
        particles.burst(ram.x, ram.y, Ram.AURA, 50, w * 0.06f)
        newRecord = if (!daily) prefs.submitScore(score) else false
        prefs.submitHeads(headsSevered)
        bestScore = prefs.bestScore
        if (daily) dailyStreak = prefs.registerDailyCompletion(dateLabel, yesterdayLabel())
        state = State.GAME_OVER
        gameOverAt = System.currentTimeMillis()
    }

    // ----------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------

    private fun drawGame(canvas: Canvas) {
        drawBackground(canvas)
        // Brief screen shake on clashes/hits — applied to the world only, not the HUD.
        val shaking = shake > 0.2f
        if (shaking) {
            canvas.save()
            canvas.translate(
                (Math.random().toFloat() - 0.5f) * shake * 2f,
                (Math.random().toFloat() - 0.5f) * shake * 2f
            )
        }
        ravan.draw(canvas, rage)
        // Telegraph: the active head pulses just before Ravan looses an attack.
        if (state == State.PLAYING && attackTimer in 1..16) {
            val f = 1f - attackTimer / 16f
            ringPaint.color = ((0x30 + (0x70 * f).toInt()) shl 24) or 0x00FF4D5E
            ringPaint.strokeWidth = h * 0.005f
            canvas.drawCircle(ravan.activeHeadX(), ravan.activeHeadY(), w * (0.06f + 0.04f * f), ringPaint)
        }
        for (ay in aayudhas) ay.draw(canvas)
        for (a in arrows) a.draw(canvas)
        for (s in astras) s.draw(canvas, w, h)
        if (this::ram.isInitialized && state != State.GAME_OVER) ram.draw(canvas)
        particles.draw(canvas)
        if (shaking) canvas.restore()

        if (state == State.PLAYING && touchMode == Touch.AIM) drawAimPreview(canvas)

        if (hurtFlash > 0.02f) {
            bgPaint.color = ((0x66 * hurtFlash).toInt().coerceIn(0, 255) shl 24) or 0x00FF3030
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }

        when (state) {
            State.READY -> drawReady(canvas)
            State.PLAYING -> drawHud(canvas)
            State.CHOOSING_BOON -> { drawHud(canvas); drawBoon(canvas) }
            State.PAUSED -> { drawHud(canvas); drawPaused(canvas) }
            State.GAME_OVER -> drawGameOver(canvas)
        }
        if (victoryBanner > 0 && state == State.PLAYING) drawVictoryBanner(canvas)
    }

    /** Dashed guide line + power gauge for the Pocket-Tanks-style aimed shot. */
    private fun drawAimPreview(canvas: Canvas) {
        val ox = ram.x; val oy = ram.y - ram.radius
        val dx = aimX - ox; val dy = aimY - oy
        val dist = kotlin.math.hypot(dx, dy)
        if (dist < ram.radius * 0.6f) return
        val dirX = dx / dist; val dirY = dy / dist
        val valid = dirY < -0.15f
        val power = (dist / (h * 0.42f)).coerceIn(0.35f, 1f)
        val col = if (valid) 0xFFFFF3C0.toInt() else 0xFFFF5A5A.toInt()

        ringPaint.color = (0xCC shl 24) or (col and 0x00FFFFFF)
        ringPaint.strokeWidth = h * 0.004f
        var t = ram.radius
        val seg = h * 0.022f
        while (t < h) {
            val y1 = oy + dirY * t
            if (y1 < 0f) break
            canvas.drawLine(ox + dirX * t, y1, ox + dirX * (t + seg * 0.55f), oy + dirY * (t + seg * 0.55f), ringPaint)
            t += seg
        }
        barPaint.color = (0xAA shl 24) or (col and 0x00FFFFFF)
        canvas.drawCircle(aimX, aimY, h * 0.009f, barPaint)

        // Power gauge under Ram.
        val bw = w * 0.2f; val bx = ox - bw / 2f; val by = oy + ram.radius * 1.9f; val bh = h * 0.012f
        barPaint.color = 0x44FFFFFF
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2f, bh / 2f, barPaint)
        barPaint.color = if (valid) Ram.GOLD else 0xFFFF5A5A.toInt()
        canvas.drawRoundRect(bx, by, bx + bw * power, by + bh, bh / 2f, bh / 2f, barPaint)
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        // Drifting embers for atmosphere.
        barPaint.color = 0x33FF8A3A.toInt()
        for (e in 0 until 14) {
            val seed = e * 97.3f
            val ex = (seed * 13f + bgPhase * (0.4f + (e % 3) * 0.2f)) % w
            val ey = h - ((seed * 31f + bgPhase * (0.6f + (e % 4) * 0.3f)) % h)
            canvas.drawCircle(ex, ey, w * 0.004f, barPaint)
        }
    }

    private fun heartPath(cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy + s * 0.7f)
        path.cubicTo(cx - s * 1.4f, cy - s * 0.4f, cx - s * 0.5f, cy - s * 1.1f, cx, cy - s * 0.3f)
        path.cubicTo(cx + s * 0.5f, cy - s * 1.1f, cx + s * 1.4f, cy - s * 0.4f, cx, cy + s * 0.7f)
        path.close()
    }

    private fun drawHud(canvas: Canvas) {
        // Score.
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.05f
        canvas.drawText(score.toString(), w / 2f, h * 0.30f, textPaint)
        textPaint.textSize = h * 0.022f
        textPaint.color = if (rage) 0xFFFF6A6A.toInt() else 0xAAFFFFFF.toInt()
        val headsLabel = if (rage) "RAGE OF RAVAN  •  HEADS $headsSevered" else "HEADS $headsSevered / 10"
        canvas.drawText(headsLabel, w / 2f, h * 0.33f, textPaint)
        if (clashCount > 0) {
            textPaint.textSize = h * 0.02f
            textPaint.color = 0xFFFFD24A.toInt()
            canvas.drawText("ARROWS CLASHED  $clashCount", w / 2f, h * 0.355f, textPaint)
        }

        // Lives (hearts), top-left.
        val s = h * 0.018f
        for (i in 0 until ram.maxLives) {
            val cx = w * 0.06f + i * s * 3f
            val cy = h * 0.05f
            barPaint.color = if (i < ram.lives) 0xFFFF4D6A.toInt() else 0x33FFFFFF
            heartPath(cx, cy, s)
            canvas.drawPath(path, barPaint)
        }

        // Floating astra button with charge ring — translucent while charging so
        // it never blocks the battle, bright and pulsing once it's ready.
        val full = astraMeter >= 1f
        val cx = rectAstra.centerX(); val cy = rectAstra.centerY(); val r = rectAstra.width() / 2f
        barPaint.color = if (full) (armedAstra.color and 0x00FFFFFF) or 0x66000000 else 0x33101820
        canvas.drawCircle(cx, cy, r, barPaint)
        ringPaint.color = 0x33FFFFFF
        ringPaint.strokeWidth = r * 0.16f
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.color = armedAstra.color
        tmpBounds.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(tmpBounds, -90f, 360f * astraMeter, false, ringPaint)
        if (full) {
            val pulse = 0.5f + 0.5f * sin(bgPhase * 0.2f)
            ringPaint.color = (((0x40 + (0x80 * pulse).toInt()) shl 24) or (armedAstra.color and 0x00FFFFFF))
            canvas.drawCircle(cx, cy, r * (1.05f + 0.08f * pulse), ringPaint)
        }
        textPaint.color = if (full) Color.WHITE else 0x99FFFFFF.toInt()
        textPaint.textSize = r * 0.34f
        canvas.drawText(armedAstra.short, cx, cy - r * 0.05f, textPaint)
        textPaint.textSize = r * 0.24f
        textPaint.color = if (full) 0xFFFFE14A.toInt() else 0x66FFFFFF.toInt()
        canvas.drawText(if (full) "TAP!" else "ASTRA", cx, cy + r * 0.35f, textPaint)

        // First-run hints.
        if (survivalTicks < 260 && headsSevered == 0) {
            textPaint.color = 0x99FFFFFF.toInt()
            textPaint.textSize = h * 0.023f
            canvas.drawText("drag low = move    •    drag high = aim & fire", w / 2f, h * 0.55f, textPaint)
        }
    }

    private fun drawVictoryBanner(canvas: Canvas) {
        val a = (min(1f, victoryBanner / 30f) * 255).toInt().coerceIn(0, 255)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = (a shl 24) or (Ram.GOLD and 0x00FFFFFF)
        textPaint.textSize = h * 0.05f
        canvas.drawText("RAVAN VANQUISHED!", w / 2f, h * 0.46f, textPaint)
        textPaint.textSize = h * 0.028f
        textPaint.color = (a shl 24) or 0x00FF6A6A
        canvas.drawText("his rage begins anew…", w / 2f, h * 0.50f, textPaint)
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, fill: Int, txt: Int, size: Float) {
        barPaint.color = fill
        val rad = r.height() / 2f
        canvas.drawRoundRect(r, rad, rad, barPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = txt
        textPaint.textSize = size
        canvas.drawText(label, r.centerX(), r.centerY() + size * 0.35f, textPaint)
    }

    private fun drawReady(canvas: Canvas) {
        val cx = w / 2f
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.textAlign = Paint.Align.CENTER

        textPaint.color = Ram.GOLD
        textPaint.textSize = h * 0.10f
        canvas.drawText("ASTRA", cx, h * 0.17f, textPaint)
        textPaint.color = 0xFFFF6A6A.toInt()
        textPaint.textSize = h * 0.035f
        canvas.drawText("RAM   vs   RAVAN", cx, h * 0.215f, textPaint)

        val modeFill = if (daily) 0xFF3A2A12.toInt() else 0xFF1A2230.toInt()
        val modeTxt = if (daily) Ram.GOLD else 0xCCFFFFFF.toInt()
        val modeLabel = if (daily) "DAILY BATTLE  •  $dateLabel" else "ENDLESS WAR"
        drawButton(canvas, rectMode, modeLabel, modeFill, modeTxt, h * 0.026f)
        textPaint.textSize = h * 0.02f
        textPaint.color = 0x99FFFFFF.toInt()
        canvas.drawText(
            if (daily) "same battle for everyone today  •  streak $dailyStreak" else "tap to switch mode",
            cx, rectMode.bottom + h * 0.032f, textPaint
        )

        val fxOn = sound.enabled
        drawButton(canvas, rectFx, if (fxOn) "FX: ON" else "FX: OFF",
            if (fxOn) 0xFF1A2230.toInt() else 0xFF2A1A1A.toInt(),
            if (fxOn) 0xCCFFFFFF.toInt() else 0x77FFFFFF.toInt(), h * 0.02f)

        // How to play.
        textPaint.color = 0xCCFFFFFF.toInt()
        textPaint.textSize = h * 0.026f
        canvas.drawText("Drag DOWN LOW to move & dodge", cx, h * 0.435f, textPaint)
        canvas.drawText("Drag UP HIGH to aim, release to fire", cx, h * 0.475f, textPaint)
        canvas.drawText("Your bow also auto-fires", cx, h * 0.515f, textPaint)
        canvas.drawText("Fill the meter, TAP the Astra orb", cx, h * 0.555f, textPaint)
        textPaint.color = 0x88FFFFFF.toInt()
        textPaint.textSize = h * 0.022f
        canvas.drawText("Sever all 10 heads of Ravan", cx, h * 0.595f, textPaint)

        val pulse = 0.5f + 0.5f * sin(bgPhase * 0.08f)
        barPaint.color = Ram.SAFFRON
        barPaint.alpha = (200 + 55 * pulse).toInt()
        val pr = rectPlay.height() / 2f
        canvas.drawRoundRect(rectPlay, pr, pr, barPaint)
        barPaint.alpha = 255
        textPaint.color = 0xFF2A1206.toInt()
        textPaint.textSize = h * 0.04f
        canvas.drawText("BEGIN BATTLE", cx, rectPlay.centerY() + h * 0.014f, textPaint)

        textPaint.color = 0x99FFFFFF.toInt()
        textPaint.textSize = h * 0.024f
        canvas.drawText("BEST $bestScore   •   MOST HEADS ${prefs.mostHeads}", cx, h * 0.80f, textPaint)
    }

    private fun drawBoon(canvas: Canvas) {
        val cx = w / 2f
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFFFF6A6A.toInt()
        textPaint.textSize = h * 0.034f
        canvas.drawText("A HEAD FALLS!", cx, h * 0.30f, textPaint)
        textPaint.color = Ram.GOLD
        textPaint.textSize = h * 0.045f
        canvas.drawText("CHOOSE A VARDAAN", cx, h * 0.355f, textPaint)

        for (i in offered.indices) {
            val r = boonRects[i]; val b = offered[i]
            barPaint.color = 0xFF161C26.toInt()
            canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, barPaint)
            ringPaint.color = b.color
            ringPaint.strokeWidth = h * 0.004f
            canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, ringPaint)
            barPaint.color = b.color
            canvas.drawCircle(r.left + r.height() * 0.5f, r.centerY(), r.height() * 0.22f, barPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = b.color
            textPaint.textSize = h * 0.03f
            canvas.drawText(b.name, r.left + r.height() * 0.95f, r.centerY() - h * 0.005f, textPaint)
            textPaint.color = 0xCCFFFFFF.toInt()
            textPaint.textSize = h * 0.022f
            canvas.drawText(b.desc, r.left + r.height() * 0.95f, r.centerY() + h * 0.028f, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawPaused(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.06f
        canvas.drawText("PAUSED", w / 2f, h * 0.46f, textPaint)
        textPaint.textSize = h * 0.03f
        canvas.drawText("Tap to resume", w / 2f, h * 0.54f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        val cx = w / 2f
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.textAlign = Paint.Align.CENTER

        textPaint.color = 0xFFFF4D6A.toInt()
        textPaint.textSize = h * 0.06f
        canvas.drawText("RAM HAS FALLEN", cx, h * 0.26f, textPaint)

        textPaint.color = if (daily) Ram.GOLD else 0x88FFFFFF.toInt()
        textPaint.textSize = h * 0.026f
        canvas.drawText(if (daily) "DAILY BATTLE  •  $dateLabel" else "ENDLESS WAR", cx, h * 0.31f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.10f
        canvas.drawText(score.toString(), cx, h * 0.42f, textPaint)

        textPaint.textSize = h * 0.03f
        if (rage) {
            textPaint.color = Ram.GOLD
            canvas.drawText("RAVAN SLAIN  •  $headsSevered HEADS SEVERED", cx, h * 0.47f, textPaint)
        } else if (newRecord) {
            textPaint.color = Ram.GOLD
            canvas.drawText("NEW BEST!  •  $headsSevered / 10 heads", cx, h * 0.47f, textPaint)
        } else {
            textPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText("$headsSevered / 10 heads  •  BEST $bestScore", cx, h * 0.47f, textPaint)
        }

        if (daily) {
            textPaint.color = Ram.GOLD
            textPaint.textSize = h * 0.024f
            canvas.drawText("DAY STREAK $dailyStreak", cx, h * 0.515f, textPaint)
        }

        drawButton(canvas, rectRetry, "FIGHT AGAIN", Ram.SAFFRON, 0xFF2A1206.toInt(), h * 0.036f)
        drawButton(canvas, rectShare, "SHARE", 0xFF2C3A52.toInt(), Color.WHITE, h * 0.034f)
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
        @Volatile var running = false
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
                        try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
                    }
                }
                val elapsed = System.currentTimeMillis() - frameStart
                val sleep = targetFrameMs - elapsed
                if (sleep > 0) {
                    try { sleep(sleep) } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
