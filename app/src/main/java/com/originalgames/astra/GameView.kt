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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * ASTRA — RAM vs RAVAN: a landscape, side-view artillery duel in the spirit of
 * Pocket Tanks. Ram (player, left) and Ravan (the ten-headed boss, right) trade
 * turns lobbing weapons across a varied, destructible terrain. Each turn the
 * player picks a weapon and drags to set aim angle + power (a live trajectory
 * preview shows the arc), then releases to fire. Wind nudges every shot.
 *
 * Everything is drawn from primitive shapes and every sound is synthesized at
 * runtime — no imported images, fonts, or audio.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { READY, PLAYER_AIM, FLYING, ENEMY_THINK, PAUSED, GAME_OVER }
    private enum class Turn { PLAYER, ENEMY }

    private val prefs = Prefs(context)
    private val sound = SoundManager(prefs.soundEnabled)
    private val haptics = Haptics(context, prefs.hapticsEnabled)
    private val particles = ParticleSystem()

    private var thread: GameThread? = null
    private var state = State.READY

    private var w = 0f
    private var h = 0f
    private var unit = 0f

    private lateinit var terrain: Terrain
    private lateinit var ram: Fighter
    private lateinit var ravan: Fighter
    private val projectiles = ArrayList<Projectile>()

    private var turn = Turn.PLAYER
    private var selected = Weapon.BAAN
    private val ammo = HashMap<Weapon, Int>()
    private var wind = 0f
    private var round = 1
    private var damageDealt = 0
    private var thinkTimer = 0
    private var gameOverAt = 0L
    private var playerWon = false
    private var bgPhase = 0f
    private var coins = 0

    // Aim input.
    private var aiming = false
    private var aimX = 0f
    private var aimY = 0f

    // UI rects.
    private val rectBegin = RectF()
    private val rectRetry = RectF()
    private val rectShare = RectF()
    private val rectPause = RectF()
    private val rectFx = RectF()
    private val weaponRects = Array(Weapon.entries.size) { RectF() }

    // Paints.
    private val skyPaint = Paint()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = 0xC0000000.toInt() }
    private val path = Path()

    // Tuned so a full-power 45° shot comfortably spans even an ultra-wide screen.
    private val gravity get() = h * 0.0012f
    private val minV get() = h * 0.016f
    private val maxV get() = h * 0.055f

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // --------------------------------------------------------------- lifecycle
    override fun surfaceCreated(holder: SurfaceHolder) {
        thread = GameThread(holder).also { it.running = true; it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        unit = h * 0.014f
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF5B3A8A.toInt(), 0xFFC9628A.toInt(), 0xFFF2A65A.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        layoutUi()
        newBattle()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.let {
            it.running = false
            var retry = true
            while (retry) { try { it.join(); retry = false } catch (_: InterruptedException) {} }
        }
        thread = null
    }

    fun onActivityPause() { if (state == State.PLAYER_AIM) state = State.PAUSED }

    private fun layoutUi() {
        val bw = w * 0.28f; val bh = h * 0.11f; val cx = w / 2f
        rectBegin.set(cx - bw / 2f, h * 0.66f, cx + bw / 2f, h * 0.66f + bh)
        rectRetry.set(cx - bw / 2f, h * 0.62f, cx + bw / 2f, h * 0.62f + bh)
        rectShare.set(cx - bw / 2f, h * 0.76f, cx + bw / 2f, h * 0.76f + bh * 0.8f)
        val ic = h * 0.09f
        rectPause.set(w - ic - w * 0.02f, h * 0.03f, w - w * 0.02f, h * 0.03f + ic)
        rectFx.set(w - ic * 2 - w * 0.035f, h * 0.03f, w - ic - w * 0.035f, h * 0.03f + ic)
        // Weapon selector row along the bottom.
        val n = weaponRects.size
        val gap = w * 0.012f
        val ww = (w * 0.5f - gap * (n - 1)) / n
        val wh = h * 0.12f
        val startX = w * 0.28f
        for (i in 0 until n) {
            val l = startX + i * (ww + gap)
            weaponRects[i].set(l, h - wh - h * 0.03f, l + ww, h - h * 0.03f)
        }
    }

    // --------------------------------------------------------------- flow
    private fun newBattle() {
        if (w <= 0f) return
        projectiles.clear(); particles.clear()
        terrain = Terrain(w, h, Random.nextLong())
        val ramX = w * 0.12f
        val ravX = w * 0.88f
        ram = Fighter(true, ramX, terrain.heightAt(ramX), h * 0.11f, 100f)
        ravan = Fighter(false, ravX, terrain.heightAt(ravX), h * 0.14f, 110f)
        ammo.clear()
        for (wp in Weapon.entries) ammo[wp] = wp.ammo
        selected = Weapon.BAAN
        turn = Turn.PLAYER
        round = 1
        damageDealt = 0
        coins = 0
        rollWind()
        aiming = false
    }

    private fun rollWind() { wind = (Random.nextFloat() - 0.5f) * gravity * 0.9f }

    private fun startBattle() { newBattle(); state = State.PLAYER_AIM; sound.confirm() }

    private fun ammoOf(wp: Weapon) = if (wp.unlimited) -1 else (ammo[wp] ?: 0)

    // --------------------------------------------------------------- input
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = event.actionMasked; val x = event.x; val y = event.y
        synchronized(holder) {
            when (state) {
                State.READY -> if (a == MotionEvent.ACTION_DOWN && rectBegin.contains(x, y)) startBattle()
                State.PLAYER_AIM -> handleAim(a, x, y)
                State.PAUSED -> if (a == MotionEvent.ACTION_DOWN) state = State.PLAYER_AIM
                State.GAME_OVER -> if (a == MotionEvent.ACTION_DOWN) handleGameOverTap(x, y)
                else -> {}
            }
        }
        return true
    }

    private fun handleAim(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (rectPause.contains(x, y)) { state = State.PAUSED; return }
                if (rectFx.contains(x, y)) { toggleFx(); return }
                for (i in weaponRects.indices) {
                    if (weaponRects[i].contains(x, y)) {
                        val wp = Weapon.entries[i]
                        if (ammoOf(wp) != 0) { selected = wp; sound.confirm() }
                        return
                    }
                }
                aiming = true; aimX = x; aimY = y
            }
            MotionEvent.ACTION_MOVE -> if (aiming) { aimX = x; aimY = y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (aiming) { aiming = false; firePlayer() }
            }
        }
    }

    private fun toggleFx() {
        val on = !sound.enabled
        sound.enabled = on; haptics.enabled = on
        prefs.soundEnabled = on; prefs.hapticsEnabled = on
        if (on) { sound.confirm(); haptics.light() }
    }

    private fun handleGameOverTap(x: Float, y: Float) {
        if (rectShare.contains(x, y)) {
            ShareCard.share(context, playerWon, round, damageDealt); return
        }
        if (System.currentTimeMillis() - gameOverAt > 400) { state = State.READY }
    }

    private fun aimVelocity(mx: Float, my: Float): FloatArray {
        val dx = aimX - mx; val dy = aimY - my
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val power = (d / (w * 0.38f)).coerceIn(0.16f, 1f)
        val v = minV + power * (maxV - minV)
        return floatArrayOf(dx / d * v, dy / d * v, power)
    }

    private fun firePlayer() {
        val wp = selected
        if (ammoOf(wp) == 0) return
        val mx = ram.muzzleX(); val my = ram.muzzleY()
        val vel = aimVelocity(mx, my)
        if (vel[2] < 0.17f) return
        launch(wp, mx, my, vel[0], vel[1])
        if (!wp.unlimited) ammo[wp] = (ammo[wp] ?: 1) - 1
        turn = Turn.PLAYER
        state = State.FLYING
        sound.shoot(); haptics.light()
    }

    private fun launch(wp: Weapon, mx: Float, my: Float, vx: Float, vy: Float) {
        val base = kotlin.math.atan2(vy, vx)
        val n = wp.shots
        for (i in 0 until n) {
            val spread = if (n == 1) 0f else (i - (n - 1) / 2f) * 0.12f
            val sp = hypot(vx, vy)
            val ang = base + spread
            projectiles.add(Projectile(mx, my, cos(ang) * sp, sin(ang) * sp, wp, gravity, wind))
        }
    }

    // --------------------------------------------------------------- update
    private fun update() {
        bgPhase += 1f
        particles.update()
        if (this::ram.isInitialized) { ram.update(); ravan.update() }

        when (state) {
            State.FLYING -> updateProjectiles()
            State.ENEMY_THINK -> { if (--thinkTimer <= 0) enemyFire() }
            else -> {}
        }
    }

    private fun updateProjectiles() {
        var i = projectiles.size - 1
        while (i >= 0) {
            val p = projectiles[i]
            p.update()
            var boom = false
            if (p.y > h || p.x < -w * 0.1f || p.x > w * 1.1f) { projectiles.removeAt(i); i--; continue }
            if (p.y < -h) { projectiles.removeAt(i); i--; continue }
            // Direct fighter hit?
            val target = if (turn == Turn.PLAYER) ravan else ram
            if (target.hitBy(p.x, p.y, unit) > 0f || terrain.collides(p.x, p.y)) boom = true
            if (boom) { explode(p); projectiles.removeAt(i) }
            i--
        }
        if (projectiles.isEmpty() && state == State.FLYING) endTurn()
    }

    private fun explode(p: Projectile) {
        val blast = p.weapon.blastFrac * w
        val r = if (blast > 0f) blast else unit * 2f
        particles.burst(p.x, p.y, p.weapon.color, if (blast > 0f) 46 else 16, r * 0.9f, gravity = 0.6f)
        particles.burst(p.x, p.y, 0xFFFFF0C0.toInt(), 10, r * 0.5f, gravity = 0.3f)
        if (blast > 0f) { terrain.crater(p.x, r * 0.7f); sound.sever() } else sound.hit()
        haptics.heavy()
        applyDamage(ram, p); applyDamage(ravan, p)
    }

    private fun applyDamage(f: Fighter, p: Projectile) {
        val blast = p.weapon.blastFrac * w
        val frac = f.hitBy(p.x, p.y, blast)
        if (frac <= 0f) return
        // Enemy shots at Ram hit a little softer to keep the duel fair.
        val enemyScale = if (f === ram && turn == Turn.ENEMY) 0.7f else 1f
        val dmg = p.weapon.damage * (0.5f + 0.5f * frac) * enemyScale
        f.hp = (f.hp - dmg).coerceAtLeast(0f)
        f.hurt = 12f
        if (f === ravan && turn == Turn.PLAYER) {
            damageDealt += dmg.toInt(); coins += (dmg / 5).toInt() + 1
        }
    }

    private fun endTurn() {
        if (ram.hp <= 0f || ravan.hp <= 0f) { finish(); return }
        if (turn == Turn.PLAYER) {
            turn = Turn.ENEMY; state = State.ENEMY_THINK; thinkTimer = 55
        } else {
            round++; rollWind(); state = State.PLAYER_AIM
        }
    }

    private fun finish() {
        playerWon = ravan.hp <= 0f
        if (playerWon) { prefs.wins += 1; sound.victory() } else sound.hurt()
        prefs.submitScore(damageDealt)
        state = State.GAME_OVER
        gameOverAt = System.currentTimeMillis()
    }

    /** Ravan's AI: search elevation/power to land near Ram, then add error that shrinks over rounds. */
    private fun enemyFire() {
        val mx = ravan.muzzleX(); val my = ravan.muzzleY()
        val targetX = ram.x
        var bestVx = -maxV * 0.6f; var bestVy = -maxV * 0.5f; var bestErr = Float.MAX_VALUE
        var deg = 25
        while (deg <= 70) {
            var speed = minV
            while (speed <= maxV) {
                val ang = Math.toRadians(deg.toDouble())
                val vx = (-cos(ang) * speed).toFloat()
                val vy = (-sin(ang) * speed).toFloat()
                val land = simulateLandingX(mx, my, vx, vy)
                if (land >= 0f) {
                    val err = abs(land - targetX)
                    if (err < bestErr) { bestErr = err; bestVx = vx; bestVy = vy }
                }
                speed += (maxV - minV) / 8f
            }
            deg += 5
        }
        // Accuracy improves with the round number; early shots miss wide.
        val errAng = (0.20f / (1f + round * 0.45f)) * (Random.nextFloat() - 0.5f) * 2f
        val ca = cos(errAng); val sa = sin(errAng)
        val rvx = bestVx * ca - bestVy * sa
        val rvy = bestVx * sa + bestVy * ca
        // Enemy mostly uses Baan, occasionally a heavier weapon.
        val wp = when (Random.nextInt(6)) { 0 -> Weapon.AGNI; 1 -> Weapon.GADA; else -> Weapon.BAAN }
        launch(wp, mx, my, rvx, rvy)
        turn = Turn.ENEMY
        state = State.FLYING
        sound.shoot()
    }

    private fun simulateLandingX(mx: Float, my: Float, vx0: Float, vy0: Float): Float {
        var x = mx; var y = my; var vx = vx0; var vy = vy0
        var steps = 0
        while (steps < 600) {
            vy += gravity; vx += wind; x += vx; y += vy
            if (x < 0f || x > w) return -1f
            if (y >= terrain.heightAt(x)) return x
            steps++
        }
        return -1f
    }

    // --------------------------------------------------------------- render
    private fun drawGame(canvas: Canvas) {
        drawBackground(canvas)
        if (!this::terrain.isInitialized) return
        terrain.draw(canvas)
        ravan.draw(canvas); ram.draw(canvas)
        ravan.drawHealthBar(canvas, textPaint); ram.drawHealthBar(canvas, textPaint)
        for (p in projectiles) p.draw(canvas, unit)
        particles.draw(canvas)

        when (state) {
            State.READY -> drawReady(canvas)
            State.PLAYER_AIM -> { if (aiming) drawTrajectory(canvas); drawHud(canvas) }
            State.FLYING -> drawHud(canvas)
            State.ENEMY_THINK -> { drawHud(canvas); drawBanner(canvas, "RAVAN ATTACKS", Palette.DEMON) }
            State.PAUSED -> { drawHud(canvas); drawPaused(canvas) }
            State.GAME_OVER -> drawGameOver(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        // Sun.
        bgPaint.color = 0x55FFF2C0.toInt()
        canvas.drawCircle(w * 0.5f, h * 0.32f, h * 0.14f, bgPaint)
        bgPaint.color = 0xAAFFE9A8.toInt()
        canvas.drawCircle(w * 0.5f, h * 0.32f, h * 0.09f, bgPaint)
        // Distant mountains.
        bgPaint.color = 0x552A1533
        drawHill(canvas, w * 0.2f, h * 0.72f, w * 0.5f)
        drawHill(canvas, w * 0.7f, h * 0.72f, w * 0.55f)
        // Faint temple silhouette centre.
        bgPaint.color = 0x33301028
        val tx = w * 0.5f; val ty = h * 0.72f
        path.reset()
        path.moveTo(tx - w * 0.05f, ty)
        path.lineTo(tx - w * 0.05f, ty - h * 0.22f)
        path.lineTo(tx, ty - h * 0.34f)
        path.lineTo(tx + w * 0.05f, ty - h * 0.22f)
        path.lineTo(tx + w * 0.05f, ty)
        path.close()
        canvas.drawPath(path, bgPaint)
    }

    private fun drawHill(canvas: Canvas, cx: Float, baseY: Float, width: Float) {
        path.reset()
        path.moveTo(cx - width / 2f, baseY)
        path.lineTo(cx, baseY - width * 0.35f)
        path.lineTo(cx + width / 2f, baseY)
        path.close()
        canvas.drawPath(path, bgPaint)
    }

    private fun drawTrajectory(canvas: Canvas) {
        val mx = ram.muzzleX(); val my = ram.muzzleY()
        val vel = aimVelocity(mx, my)
        var x = mx; var y = my; var vx = vel[0]; var vy = vel[1]
        barPaint.color = 0x99FFFFFF.toInt()
        var steps = 0; var dot = 0
        while (steps < 260) {
            vy += gravity; vx += wind; x += vx; y += vy
            if (x < 0f || x > w || y > h) break
            if (terrain.collides(x, y)) break
            if (dot % 6 == 0) canvas.drawCircle(x, y, unit * 0.28f, barPaint)
            dot++; steps++
        }
        // Angle + power readout.
        val ang = Math.toDegrees(kotlin.math.atan2(-vy.toDouble(), vx.toDouble())).toInt()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Palette.GOLD
        textPaint.textSize = h * 0.045f
        canvas.drawText("${abs(ang)}°   PWR ${(vel[2] * 100).toInt()}%", w * 0.04f, h * 0.20f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawHud(canvas: Canvas) {
        // Coins top-left.
        barPaint.color = Palette.GOLD
        canvas.drawCircle(w * 0.045f, h * 0.06f, h * 0.022f, barPaint)
        textPaint.color = 0xFF5A3A00.toInt(); textPaint.textSize = h * 0.026f
        canvas.drawText("₹", w * 0.045f, h * 0.069f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.036f
        canvas.drawText("$coins", w * 0.07f, h * 0.072f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Round + wind top-centre.
        textPaint.color = 0xCCFFFFFF.toInt(); textPaint.textSize = h * 0.032f
        canvas.drawText("ROUND $round", w * 0.5f, h * 0.07f, textPaint)
        drawWind(canvas)

        // Icon buttons.
        drawIcon(canvas, rectPause, "II")
        drawIcon(canvas, rectFx, if (sound.enabled) "♪" else "x")

        // Weapon selector.
        for (i in weaponRects.indices) {
            val r = weaponRects[i]; val wp = Weapon.entries[i]
            val sel = wp == selected
            val out = ammoOf(wp) == 0
            barPaint.color = when { out -> 0x33202020; sel -> (wp.color and 0x00FFFFFF) or 0x99000000.toInt(); else -> 0xAA202832.toInt() }
            canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, barPaint)
            if (sel) { ringPaint.color = wp.color; ringPaint.strokeWidth = h * 0.006f; canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, ringPaint) }
            barPaint.color = if (out) 0x55FFFFFF else wp.color
            canvas.drawCircle(r.centerX(), r.top + r.height() * 0.34f, r.height() * 0.16f, barPaint)
            textPaint.color = if (out) 0x66FFFFFF else Color.WHITE
            textPaint.textSize = r.height() * 0.16f
            canvas.drawText(wp.label, r.centerX(), r.top + r.height() * 0.62f, textPaint)
            textPaint.textSize = r.height() * 0.15f
            textPaint.color = 0xAAFFFFFF.toInt()
            canvas.drawText(if (wp.unlimited) "∞" else "x${ammoOf(wp)}", r.centerX(), r.bottom - r.height() * 0.12f, textPaint)
        }

        if (state == State.PLAYER_AIM && !aiming) {
            textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.03f
            canvas.drawText("YOUR TURN — drag from Ram to aim, release to fire", w * 0.5f, h * 0.62f, textPaint)
        }
    }

    private fun drawWind(canvas: Canvas) {
        textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.026f
        canvas.drawText("WIND", w * 0.5f, h * 0.11f, textPaint)
        val mag = (wind / (gravity * 0.9f)).coerceIn(-1f, 1f)
        val ax = w * 0.5f; val ay = h * 0.135f; val len = w * 0.06f * abs(mag) + w * 0.01f
        ringPaint.color = if (mag >= 0f) Palette.GRASS else Palette.DEMON
        ringPaint.strokeWidth = h * 0.006f; ringPaint.strokeCap = Paint.Cap.ROUND
        val dir = if (mag >= 0f) 1f else -1f
        canvas.drawLine(ax - len * dir, ay, ax + len * dir, ay, ringPaint)
        canvas.drawLine(ax + len * dir, ay, ax + (len - w * 0.02f) * dir, ay - h * 0.014f, ringPaint)
        canvas.drawLine(ax + len * dir, ay, ax + (len - w * 0.02f) * dir, ay + h * 0.014f, ringPaint)
    }

    private fun drawIcon(canvas: Canvas, r: RectF, glyph: String) {
        barPaint.color = 0xAA1A2230.toInt()
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, barPaint)
        ringPaint.color = 0x55FFFFFF; ringPaint.strokeWidth = r.width() * 0.05f
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, ringPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = r.height() * 0.4f
        canvas.drawText(glyph, r.centerX(), r.centerY() + r.height() * 0.14f, textPaint)
    }

    private fun drawBanner(canvas: Canvas, msg: String, color: Int) {
        textPaint.color = color; textPaint.textSize = h * 0.06f
        val a = 0.6f + 0.4f * sin(bgPhase * 0.15f)
        textPaint.alpha = (255 * a).toInt()
        canvas.drawText(msg, w * 0.5f, h * 0.4f, textPaint)
        textPaint.alpha = 255
    }

    private fun drawReady(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        val cx = w / 2f
        textPaint.color = Palette.GOLD; textPaint.textSize = h * 0.13f
        canvas.drawText("ASTRA", cx, h * 0.24f, textPaint)
        textPaint.color = Palette.DEMON; textPaint.textSize = h * 0.05f
        canvas.drawText("RAM   vs   RAVAN", cx, h * 0.32f, textPaint)
        textPaint.color = 0xAAFFD24A.toInt(); textPaint.textSize = h * 0.032f
        canvas.drawText("An artillery duel — aim, power & astras over the battlefield", cx, h * 0.40f, textPaint)

        val pulse = 0.5f + 0.5f * sin(bgPhase * 0.08f)
        barPaint.color = Palette.SAFFRON; barPaint.alpha = (200 + 55 * pulse).toInt()
        val pr = rectBegin.height() / 2f
        canvas.drawRoundRect(rectBegin, pr, pr, barPaint); barPaint.alpha = 255
        textPaint.color = 0xFF2A1206.toInt(); textPaint.textSize = h * 0.05f
        canvas.drawText("BEGIN  DUEL", cx, rectBegin.centerY() + h * 0.017f, textPaint)

        textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.032f
        canvas.drawText("WINS ${prefs.wins}    •    BEST DMG ${prefs.bestScore}", cx, h * 0.86f, textPaint)
    }

    private fun drawPaused(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.09f
        canvas.drawText("PAUSED", w / 2f, h * 0.46f, textPaint)
        textPaint.textSize = h * 0.04f
        canvas.drawText("Tap to resume", w / 2f, h * 0.56f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        val cx = w / 2f
        textPaint.color = if (playerWon) Palette.GOLD else Palette.DEMON
        textPaint.textSize = h * 0.10f
        canvas.drawText(if (playerWon) "RAVAN SLAIN!" else "RAM HAS FALLEN", cx, h * 0.30f, textPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.045f
        canvas.drawText("Rounds $round   •   Damage $damageDealt   •   ₹$coins", cx, h * 0.40f, textPaint)
        textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.032f
        canvas.drawText("WINS ${prefs.wins}", cx, h * 0.47f, textPaint)

        drawButton(canvas, rectRetry, "FIGHT AGAIN", Palette.SAFFRON, 0xFF2A1206.toInt(), h * 0.045f)
        drawButton(canvas, rectShare, "SHARE & CHALLENGE", 0xFF2C3A52.toInt(), Color.WHITE, h * 0.036f)
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, fill: Int, txt: Int, size: Float) {
        barPaint.color = fill
        val rad = r.height() / 2f
        canvas.drawRoundRect(r, rad, rad, barPaint)
        textPaint.color = txt; textPaint.textSize = size
        canvas.drawText(label, r.centerX(), r.centerY() + size * 0.35f, textPaint)
    }

    // --------------------------------------------------------------- thread
    private inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        @Volatile var running = false
        private val targetFrameMs = 1000L / 60L
        override fun run() {
            while (running) {
                val frameStart = System.currentTimeMillis()
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null && w > 0f) synchronized(surfaceHolder) { update(); drawGame(canvas) }
                } finally {
                    if (canvas != null) { try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {} }
                }
                val sleep = targetFrameMs - (System.currentTimeMillis() - frameStart)
                if (sleep > 0) try { sleep(sleep) } catch (_: InterruptedException) {}
            }
        }
    }
}
