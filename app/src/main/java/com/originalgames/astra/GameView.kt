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

    private enum class State { READY, QUIZ, PLAYER_AIM, FLYING, ENEMY_THINK, PAUSED, GAME_OVER }
    private enum class Turn { PLAYER, ENEMY }

    /** Transient astra impact / helper visual effect. */
    private class Fx(val x: Float, val y: Float, val kind: Int, val maxAge: Int, val color: Int) {
        var age = 0
    }

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
    private var rathBoost = 1f
    private var bowMult = 1f
    private var enemyAccuracy = 1f
    private var gameOverAt = 0L
    private var playerWon = false
    private var bgPhase = 0f
    private var coins = 0

    // Campaign + helpers.
    private lateinit var enemyDef: Enemy
    private var hanumanUsed = false
    private var lakshmanUsed = false
    private val fxs = ArrayList<Fx>()
    private var banner = ""
    private var bannerTimer = 0
    private var bannerColor = Palette.GOLD

    // Quiz.
    private var question: Question? = null
    private var quizPicked = -1
    private var quizTimer = 0

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
    private val rectArmour = RectF()
    private val rectRath = RectF()
    private val rectBow = RectF()
    private val rectHanuman = RectF()
    private val rectLakshman = RectF()
    private val quizRects = Array(4) { RectF() }
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
            intArrayOf(0xFF7A4B12.toInt(), 0xFFC9871E.toInt(), 0xFFF3D27A.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
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
        val bw = w * 0.30f; val bh = h * 0.11f; val cx = w / 2f
        rectBegin.set(cx - bw / 2f, h * 0.70f, cx + bw / 2f, h * 0.70f + bh)
        // Three shop chips on the start screen.
        val uw = w * 0.22f; val uh = h * 0.14f; val ug = w * 0.02f
        val total = uw * 3 + ug * 2; val sx = cx - total / 2f; val uy = h * 0.46f
        rectArmour.set(sx, uy, sx + uw, uy + uh)
        rectRath.set(sx + uw + ug, uy, sx + uw * 2 + ug, uy + uh)
        rectBow.set(sx + uw * 2 + ug * 2, uy, sx + uw * 3 + ug * 2, uy + uh)
        rectRetry.set(cx - bw / 2f, h * 0.64f, cx + bw / 2f, h * 0.64f + bh)
        rectShare.set(cx - bw / 2f, h * 0.78f, cx + bw / 2f, h * 0.78f + bh * 0.8f)
        val ic = h * 0.09f
        rectPause.set(w - ic - w * 0.02f, h * 0.03f, w - w * 0.02f, h * 0.03f + ic)
        rectFx.set(w - ic * 2 - w * 0.035f, h * 0.03f, w - ic - w * 0.035f, h * 0.03f + ic)
        // Weapon selector row along the bottom-centre.
        val n = weaponRects.size
        val gap = w * 0.012f
        val ww = (w * 0.46f - gap * (n - 1)) / n
        val wh = h * 0.12f
        val startX = w * 0.30f
        for (i in 0 until n) {
            val l = startX + i * (ww + gap)
            weaponRects[i].set(l, h - wh - h * 0.03f, l + ww, h - h * 0.03f)
        }
        // Helper summon buttons, bottom-left.
        val hw = w * 0.115f; val hh = h * 0.11f; val hy = h - hh - h * 0.03f
        rectHanuman.set(w * 0.02f, hy, w * 0.02f + hw, hy + hh)
        rectLakshman.set(w * 0.03f + hw, hy, w * 0.03f + hw * 2, hy + hh)
        // Quiz option buttons.
        val qw = w * 0.62f; val qh = h * 0.11f; val qg = h * 0.025f
        for (i in 0 until 4) {
            val top = h * 0.40f + i * (qh + qg)
            quizRects[i].set(cx - qw / 2f, top, cx + qw / 2f, top + qh)
        }
    }

    // --------------------------------------------------------------- flow
    private fun newBattle() {
        if (w <= 0f) return
        projectiles.clear(); particles.clear()
        terrain = Terrain(w, h, Random.nextLong())
        val ramX = w * 0.12f
        val ravX = w * 0.88f
        val armour = prefs.armourOwned && prefs.armourOn
        val rath = prefs.rathOwned && prefs.rathOn
        ram = Fighter(true, ramX, terrain.heightAt(ramX), h * 0.11f, if (armour) 150f else 100f)
        ram.armour = armour; ram.rath = rath
        rathBoost = if (rath) 1.2f else 1f
        bowMult = 1f + prefs.bowLevel * 0.2f
        // Current campaign foe (Ravan is last).
        enemyDef = Enemy.ROSTER[prefs.stage.coerceIn(0, Enemy.FINAL)]
        enemyAccuracy = enemyDef.accuracy
        ravan = Fighter(false, ravX, terrain.heightAt(ravX), h * enemyDef.scale, enemyDef.hp)
        ravan.skin = enemyDef.skin; ravan.heads = enemyDef.heads
        ravan.crown = enemyDef.crown; ravan.displayName = enemyDef.name.uppercase()
        hanumanUsed = false; lakshmanUsed = false
        fxs.clear(); banner = ""; bannerTimer = 0
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

    /** Begin a stage: first the Gyaan Dwar (Ramayana quiz), then the duel. */
    private fun startBattle() {
        newBattle()
        question = Quiz.random(Random.Default)
        quizPicked = -1; quizTimer = 0
        state = State.QUIZ
        sound.confirm()
    }

    private fun ammoOf(wp: Weapon) = if (wp.unlimited) -1 else (ammo[wp] ?: 0)

    private fun handleQuizTap(x: Float, y: Float) {
        if (quizPicked >= 0) return
        val q = question ?: return
        for (i in quizRects.indices) {
            if (quizRects[i].contains(x, y)) {
                quizPicked = i
                quizTimer = 90
                if (i == q.answer) {
                    prefs.points += Prefs.QUIZ_REWARD
                    // Reward a bonus special astra shot for this battle.
                    ammo[Weapon.AGNI] = (ammo[Weapon.AGNI] ?: 0) + 1
                    sound.victory()
                } else sound.hurt()
                return
            }
        }
    }

    // --------------------------------------------------------------- input
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = event.actionMasked; val x = event.x; val y = event.y
        synchronized(holder) {
            when (state) {
                State.READY -> if (a == MotionEvent.ACTION_DOWN) handleReadyTap(x, y)
                State.QUIZ -> if (a == MotionEvent.ACTION_DOWN) handleQuizTap(x, y)
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
                if (rectHanuman.contains(x, y)) { if (!hanumanUsed) useHanuman(); return }
                if (rectLakshman.contains(x, y)) { if (!lakshmanUsed) useLakshman(); return }
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

    private fun handleReadyTap(x: Float, y: Float) {
        when {
            rectBegin.contains(x, y) -> startBattle()
            rectArmour.contains(x, y) -> {
                if (prefs.armourOwned) prefs.armourOn = !prefs.armourOn
                else if (prefs.spend(Prefs.ARMOUR_COST)) { prefs.armourOwned = true; prefs.armourOn = true }
                sound.confirm()
            }
            rectRath.contains(x, y) -> {
                if (prefs.rathOwned) prefs.rathOn = !prefs.rathOn
                else if (prefs.spend(Prefs.RATH_COST)) { prefs.rathOwned = true; prefs.rathOn = true }
                sound.confirm()
            }
            rectBow.contains(x, y) -> {
                if (prefs.bowLevel < Prefs.MAX_BOW && prefs.spend(Prefs.BOW_COST)) {
                    prefs.bowLevel += 1; sound.confirm()
                }
            }
        }
    }

    private fun useHanuman() {
        hanumanUsed = true
        ravan.hp = (ravan.hp - 45f).coerceAtLeast(0f); ravan.hurt = 12f
        ram.hp = (ram.hp + 25f).coerceAtMost(ram.maxHp)
        damageDealt += 45; coins += 10
        fxs.add(Fx(ravan.x, ravan.centerY(), FX_HANUMAN, 40, Palette.SAFFRON))
        particles.burst(ravan.x, ravan.centerY(), Palette.SAFFRON, 40, w * 0.05f, gravity = 0.4f)
        showBanner("JAI HANUMAN!", Palette.SAFFRON)
        sound.hanuman(); haptics.heavy()
        if (ravan.hp <= 0f) finish()
    }

    private fun useLakshman() {
        lakshmanUsed = true
        ravan.hp = (ravan.hp - 40f).coerceAtLeast(0f); ravan.hurt = 12f
        damageDealt += 40; coins += 10
        fxs.add(Fx(ravan.x, ravan.centerY(), FX_LAKSHMAN, 34, Palette.RAM_BLUE))
        particles.burst(ravan.x, ravan.centerY(), Palette.RAM_BLUE, 30, w * 0.04f, gravity = 0.3f)
        showBanner("LAKSHMAN'S VOLLEY!", Palette.RAM_BLUE)
        sound.lakshman(); haptics.heavy()
        if (ravan.hp <= 0f) finish()
    }

    private fun showBanner(msg: String, color: Int) { banner = msg; bannerColor = color; bannerTimer = 70 }

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
        val v = (minV + power * (maxV - minV)) * rathBoost   // Rath adds launch power
        return floatArrayOf(dx / d * v, dy / d * v, power)
    }

    private fun firePlayer() {
        val wp = selected
        if (ammoOf(wp) == 0) return
        val mx = ram.muzzleX(); val my = ram.muzzleY()
        val vel = aimVelocity(mx, my)
        if (vel[2] < 0.17f) return
        launch(wp, mx, my, vel[0], vel[1])
        // Activation burst at the muzzle, scaled by charge power (escalation).
        particles.burst(mx, my, wp.color, (12 + vel[2] * 24).toInt(), unit * (1.4f + vel[2] * 1.6f), gravity = 0.1f)
        fxs.add(Fx(mx, my, FX_CAST, 14, wp.color))
        if (!wp.unlimited) ammo[wp] = (ammo[wp] ?: 1) - 1
        turn = Turn.PLAYER
        state = State.FLYING
        sound.cast(wp); haptics.light()
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
        var fi = fxs.size - 1
        while (fi >= 0) { fxs[fi].age++; if (fxs[fi].age >= fxs[fi].maxAge) fxs.removeAt(fi); fi-- }
        if (bannerTimer > 0) bannerTimer--

        when (state) {
            State.FLYING -> updateProjectiles()
            State.ENEMY_THINK -> { if (--thinkTimer <= 0) enemyFire() }
            State.QUIZ -> { if (quizPicked >= 0 && --quizTimer <= 0) state = State.PLAYER_AIM }
            else -> {}
        }
    }

    private fun updateProjectiles() {
        var i = projectiles.size - 1
        while (i >= 0) {
            val p = projectiles[i]
            p.update()
            emitTrail(p)
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

    /** Residual in-flight particles — each astra leaves its own living wake. */
    private fun emitTrail(p: Projectile) {
        when (p.weapon) {
            Weapon.AGNI -> particles.burst(p.x, p.y, 0xFFFF7A2E.toInt(), 2, unit * 0.9f, gravity = -0.05f)
            Weapon.BRAHMA -> particles.burst(p.x, p.y, 0xFFFFE9A8.toInt(), 3, unit * 1.1f, gravity = 0f)
            Weapon.NAGA -> if (p.age % 3 == 0) particles.burst(p.x, p.y, 0xFF49E07A.toInt(), 1, unit * 0.6f, gravity = 0.1f)
            Weapon.GADA -> if (p.age % 4 == 0) particles.burst(p.x, p.y, 0xFFB07A3A.toInt(), 1, unit * 0.5f, gravity = 0.2f)
            else -> {}
        }
    }

    private fun explode(p: Projectile) {
        val blast = p.weapon.blastFrac * w
        val r = if (blast > 0f) blast else unit * 2f
        // Distinct impact per astra: particle burst + shockwave FX + its own sound.
        when (p.weapon) {
            Weapon.BAAN -> { particles.burst(p.x, p.y, p.weapon.color, 14, r * 0.9f, 0.5f); fxs.add(Fx(p.x, p.y, FX_BAAN, 16, p.weapon.color)); sound.impactBaan() }
            Weapon.AGNI -> { particles.burst(p.x, p.y, 0xFFFF6A1E.toInt(), 40, r, 0.2f); particles.burst(p.x, p.y, 0xFFFFC23A.toInt(), 20, r * 0.7f, 0.1f); fxs.add(Fx(p.x, p.y, FX_AGNI, 26, 0xFFFF6A1E.toInt())); sound.impactAgni() }
            Weapon.NAGA -> { particles.burst(p.x, p.y, 0xFF49E07A.toInt(), 28, r * 0.9f, 0.3f); fxs.add(Fx(p.x, p.y, FX_NAGA, 22, 0xFF49E07A.toInt())); sound.impactNaga() }
            Weapon.GADA -> { particles.burst(p.x, p.y, 0xFFB07A3A.toInt(), 34, r, 0.7f); fxs.add(Fx(p.x, p.y, FX_GADA, 24, 0xFFCBA36A.toInt())); sound.impactGada() }
            Weapon.BRAHMA -> { particles.burst(p.x, p.y, 0xFFFFE14A.toInt(), 60, r * 1.1f, 0.2f); particles.burst(p.x, p.y, Color.WHITE, 30, r * 0.8f, 0.1f); fxs.add(Fx(p.x, p.y, FX_BRAHMA, 34, 0xFFFFE14A.toInt())); sound.impactBrahma() }
        }
        if (blast > 0f) terrain.crater(p.x, r * 0.7f)
        haptics.heavy()
        applyDamage(ram, p); applyDamage(ravan, p)
    }

    private fun applyDamage(f: Fighter, p: Projectile) {
        val blast = p.weapon.blastFrac * w
        val frac = f.hitBy(p.x, p.y, blast)
        if (frac <= 0f) return
        // Enemy shots at Ram hit softer; Ram's shots scale with his bow upgrade.
        val enemyScale = if (f === ram && turn == Turn.ENEMY) 0.6f else 1f
        val playerScale = if (f === ravan && turn == Turn.PLAYER) bowMult else 1f
        val dmg = p.weapon.damage * (0.5f + 0.5f * frac) * enemyScale * playerScale
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
        if (playerWon) {
            prefs.wins += 1
            prefs.points += Prefs.WIN_REWARD
            if (prefs.stage >= Enemy.FINAL) { prefs.ravanWins += 1; prefs.stage = 0 }  // campaign cleared → new playthrough
            else prefs.stage += 1
            sound.victory()
        } else sound.hurt()
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
        // Accuracy improves with the round and is scaled by this foe's skill.
        val errAng = (0.22f / (enemyAccuracy * (1f + round * 0.4f))) * (Random.nextFloat() - 0.5f) * 2f
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
        for (f in fxs) drawFx(canvas, f)

        when (state) {
            State.READY -> drawReady(canvas)
            State.QUIZ -> drawQuiz(canvas)
            State.PLAYER_AIM -> { if (aiming) { drawTrajectory(canvas); drawCharge(canvas) }; drawHud(canvas) }
            State.FLYING -> drawHud(canvas)
            State.ENEMY_THINK -> { drawHud(canvas); drawBanner(canvas, "${enemyDef.name.uppercase()} ATTACKS", Palette.DEMON) }
            State.PAUSED -> { drawHud(canvas); drawPaused(canvas) }
            State.GAME_OVER -> drawGameOver(canvas)
        }
        if (bannerTimer > 0 && state != State.QUIZ) {
            val a = (255 * (bannerTimer / 70f).coerceIn(0f, 1f)).toInt()
            textPaint.color = (a shl 24) or (bannerColor and 0x00FFFFFF)
            textPaint.textSize = h * 0.07f
            canvas.drawText(banner, w * 0.5f, h * 0.34f, textPaint)
        }
    }

    private fun drawFx(canvas: Canvas, f: Fx) {
        val t = f.age.toFloat() / f.maxAge
        val a = ((1f - t) * 255).toInt().coerceIn(0, 255)
        when (f.kind) {
            FX_AGNI, FX_GADA, FX_NAGA, FX_BAAN, FX_BRAHMA, FX_CAST -> {
                ringPaint.color = (a shl 24) or (f.color and 0x00FFFFFF)
                ringPaint.strokeWidth = unit * (if (f.kind == FX_BRAHMA) 1.2f else 0.7f)
                canvas.drawCircle(f.x, f.y, t * w * (if (f.kind == FX_BRAHMA) 0.14f else if (f.kind == FX_CAST) 0.05f else 0.07f) + unit, ringPaint)
                if (f.kind == FX_BRAHMA && t < 0.4f) {
                    bgPaint.color = ((0x88 * (1 - t / 0.4f)).toInt() shl 24) or 0x00FFFFFF
                    canvas.drawRect(0f, 0f, w, h, bgPaint)
                }
            }
            FX_HANUMAN -> {
                // Hanuman's leaping smash — an orange comet arc into the foe.
                bgPaint.color = (a shl 24) or (Palette.SAFFRON and 0x00FFFFFF)
                val hx = f.x - (1 - t) * w * 0.3f; val hy = f.y - (1 - t) * (1 - t) * h * 0.4f
                canvas.drawCircle(hx, hy, unit * 2.5f, bgPaint)
                ringPaint.color = (a shl 24) or (Palette.SAFFRON and 0x00FFFFFF); ringPaint.strokeWidth = unit
                canvas.drawCircle(f.x, f.y, t * w * 0.1f + unit, ringPaint)
            }
            FX_LAKSHMAN -> {
                // Lakshman's volley — three blue streaks converging on the foe.
                stroke(a, Palette.RAM_BLUE, unit * 0.6f)
                for (k in -1..1) {
                    val sx = 0f; val sy = f.y + k * h * 0.12f
                    val px = f.x - (1 - t) * (f.x - sx); val py = f.y - (1 - t) * (f.y - sy)
                    canvas.drawLine(px - unit * 3, py - k * unit, px, py, ringPaint)
                }
            }
        }
    }

    private fun stroke(a: Int, color: Int, wdt: Float) {
        ringPaint.color = (a shl 24) or (color and 0x00FFFFFF); ringPaint.strokeWidth = wdt; ringPaint.strokeCap = Paint.Cap.ROUND
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        val horizon = h * 0.72f
        // Sun with soft radiating rays.
        val sunX = w * 0.5f; val sunY = h * 0.30f
        ringPaint.color = 0x33FFF3C0; ringPaint.strokeWidth = h * 0.006f; ringPaint.strokeCap = Paint.Cap.ROUND
        for (a in 0 until 16) {
            val an = a * (Math.PI.toFloat() / 8f) + bgPhase * 0.003f
            canvas.drawLine(sunX + cos(an) * h * 0.17f, sunY + sin(an) * h * 0.17f,
                sunX + cos(an) * h * 0.26f, sunY + sin(an) * h * 0.26f, ringPaint)
        }
        bgPaint.color = 0x55FFF6D0.toInt(); canvas.drawCircle(sunX, sunY, h * 0.15f, bgPaint)
        bgPaint.color = 0xDDFFF0B0.toInt(); canvas.drawCircle(sunX, sunY, h * 0.085f, bgPaint)

        // Golden haze band at the horizon.
        bgPaint.color = 0x33FFE9A8.toInt()
        canvas.drawRect(0f, horizon - h * 0.10f, w, horizon, bgPaint)

        // The golden City of Lanka — three receding skyline layers + a grand palace.
        drawSkyline(canvas, horizon, 0.45f, 0xFFB88A2E.toInt(), 0.6f, 0.055f)
        drawSkyline(canvas, horizon, 0.72f, 0xFFD6A63C.toInt(), 0.85f, 0.075f)
        drawPalace(canvas, horizon)
        drawSkyline(canvas, horizon, 1.0f, 0xFFF0C24E.toInt(), 1f, 0.095f)

        // Shimmering golden moat just in front of the city (Lanka is an island).
        bgPaint.color = 0x552A1A08.toInt()
        canvas.drawRect(0f, horizon, w, horizon + h * 0.03f, bgPaint)
        stroke(0x55, Palette.GOLD, h * 0.004f)
        var sx = (bgPhase * 0.5f) % (w * 0.08f)
        while (sx < w) { canvas.drawLine(sx, horizon + h * 0.015f, sx + w * 0.04f, horizon + h * 0.015f, ringPaint); sx += w * 0.08f }
    }

    /** A receding row of golden towers, domes, spires and rooftops. */
    private fun drawSkyline(canvas: Canvas, baseY: Float, sf: Float, color: Int, alpha: Float, step: Float) {
        bgPaint.color = (((0xFF * alpha).toInt() shl 24) or (color and 0x00FFFFFF))
        val wallTop = baseY - h * 0.09f * sf
        canvas.drawRect(0f, wallTop, w, baseY, bgPaint)
        var i = 0; val sw = w * step
        var bx = -sw * 0.5f
        while (bx < w) {
            val seed = (i * 37 % 7)
            val tw = sw * (0.34f + (seed % 3) * 0.06f)
            val th = h * (0.12f + (seed % 4) * 0.05f) * sf
            val txp = bx + sw * 0.5f
            canvas.drawRect(txp - tw, baseY - th, txp + tw, baseY, bgPaint)
            when (seed % 3) {
                0 -> { // onion dome + kalash
                    path.reset(); path.moveTo(txp - tw, baseY - th)
                    path.cubicTo(txp - tw, baseY - th - tw * 1.7f, txp + tw, baseY - th - tw * 1.7f, txp + tw, baseY - th)
                    path.close(); canvas.drawPath(path, bgPaint)
                    canvas.drawCircle(txp, baseY - th - tw * 1.6f, tw * 0.22f, bgPaint)
                }
                1 -> { // tall spire (shikhara)
                    path.reset(); path.moveTo(txp - tw, baseY - th); path.lineTo(txp, baseY - th - tw * 2.4f)
                    path.lineTo(txp + tw, baseY - th); path.close(); canvas.drawPath(path, bgPaint)
                }
                else -> { // flat roof + battlements
                    var c = txp - tw
                    while (c < txp + tw) { canvas.drawRect(c, baseY - th - tw * 0.35f, c + tw * 0.3f, baseY - th, bgPaint); c += tw * 0.5f }
                }
            }
            bx += sw; i++
        }
    }

    /** Ravan's grand central palace: a broad tower, big dome and flanking spires with banners. */
    private fun drawPalace(canvas: Canvas, baseY: Float) {
        val cx = w * 0.5f; val gold = 0xFFF7CE5A.toInt(); val shade = 0xFFC79A34.toInt()
        val bw = w * 0.11f; val bh = h * 0.34f
        // Main tower.
        bgPaint.color = shade; canvas.drawRect(cx - bw, baseY - bh, cx + bw, baseY, bgPaint)
        bgPaint.color = gold; canvas.drawRect(cx - bw, baseY - bh, cx + bw * 0.55f, baseY, bgPaint)
        // Grand dome.
        path.reset(); path.moveTo(cx - bw, baseY - bh)
        path.cubicTo(cx - bw, baseY - bh - bw * 1.8f, cx + bw, baseY - bh - bw * 1.8f, cx + bw, baseY - bh)
        path.close(); bgPaint.color = gold; canvas.drawPath(path, bgPaint)
        // Kalash spire on the dome.
        bgPaint.color = gold; canvas.drawRect(cx - bw * 0.06f, baseY - bh - bw * 2.9f, cx + bw * 0.06f, baseY - bh - bw * 1.7f, bgPaint)
        canvas.drawCircle(cx, baseY - bh - bw * 3.0f, bw * 0.18f, bgPaint)
        // Windows.
        bgPaint.color = 0x66301206.toInt()
        for (r in 0 until 4) canvas.drawRect(cx - bw * 0.5f, baseY - bh * (0.85f - r * 0.2f), cx + bw * 0.5f, baseY - bh * (0.78f - r * 0.2f), bgPaint)
        // Flanking spires with banners.
        for (s in intArrayOf(-1, 1)) {
            val fx = cx + s * bw * 2.2f
            bgPaint.color = shade; canvas.drawRect(fx - bw * 0.4f, baseY - bh * 0.8f, fx + bw * 0.4f, baseY, bgPaint)
            path.reset(); path.moveTo(fx - bw * 0.4f, baseY - bh * 0.8f); path.lineTo(fx, baseY - bh * 1.15f)
            path.lineTo(fx + bw * 0.4f, baseY - bh * 0.8f); path.close(); bgPaint.color = gold; canvas.drawPath(path, bgPaint)
            bgPaint.color = 0xCCFF4D5E.toInt()
            val flag = sin(bgPhase * 0.1f + s) * bw * 0.05f
            canvas.drawRect(fx, baseY - bh * 1.15f, fx + bw * 0.45f + flag, baseY - bh * 1.05f, bgPaint)
        }
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

    /** Divine energy gathering at Ram's bow as he draws — escalates with power. */
    private fun drawCharge(canvas: Canvas) {
        val mx = ram.muzzleX(); val my = ram.muzzleY()
        val power = aimVelocity(mx, my)[2]
        val c = selected.color
        val pulse = 0.75f + 0.25f * sin(bgPhase * 0.4f)
        // Halo grows with charge power (low -> high escalation).
        bgPaint.color = ((0x33 + (0x33 * power).toInt()) shl 24) or (c and 0x00FFFFFF)
        canvas.drawCircle(mx, my, unit * (1.5f + power * 4f) * pulse, bgPaint)
        // Rotating divine rays; more rays and longer as power rises.
        ringPaint.color = (0xCC shl 24) or (c and 0x00FFFFFF)
        ringPaint.strokeWidth = unit * 0.35f
        val rays = 4 + (power * 6).toInt()
        val rot = bgPhase * 0.15f
        val r1 = unit * 1.2f; val r2 = unit * (1.8f + power * 3.5f) * pulse
        for (a in 0 until rays) {
            val an = rot + a * (2f * Math.PI.toFloat() / rays)
            canvas.drawLine(mx + cos(an) * r1, my + sin(an) * r1, mx + cos(an) * r2, my + sin(an) * r2, ringPaint)
        }
        // Bright core.
        bgPaint.color = c; canvas.drawCircle(mx, my, unit * (0.6f + power * 0.9f), bgPaint)
        bgPaint.color = Color.WHITE; canvas.drawCircle(mx, my, unit * (0.3f + power * 0.4f), bgPaint)
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

        // Foe + round + wind top-centre.
        textPaint.color = Palette.DEMON; textPaint.textSize = h * 0.03f
        canvas.drawText("STAGE ${prefs.stage + 1}/${Enemy.ROSTER.size}  •  ${enemyDef.name.uppercase()}", w * 0.5f, h * 0.06f, textPaint)
        textPaint.color = 0xAAFFFFFF.toInt(); textPaint.textSize = h * 0.024f
        canvas.drawText("ROUND $round", w * 0.5f, h * 0.09f, textPaint)
        drawWind(canvas)

        // Icon buttons.
        drawIcon(canvas, rectPause, "II")
        drawIcon(canvas, rectFx, if (sound.enabled) "♪" else "x")

        // Helper summons (once each per battle).
        drawHelper(canvas, rectHanuman, "HANUMAN", hanumanUsed, Palette.SAFFRON)
        drawHelper(canvas, rectLakshman, "LAKSHMAN", lakshmanUsed, Palette.RAM_BLUE)

        // Weapon selector — Pocket-Tanks-style tiles, each with a distinct astra icon.
        for (i in weaponRects.indices) {
            val r = weaponRects[i]; val wp = Weapon.entries[i]
            val sel = wp == selected
            val out = ammoOf(wp) == 0
            val rad = h * 0.018f
            // Tile: inset "3D" look with a lighter top and a selected glow.
            barPaint.color = if (out) 0xCC161616.toInt() else 0xEE10161F.toInt()
            canvas.drawRoundRect(r, rad, rad, barPaint)
            barPaint.color = if (out) 0x22FFFFFF else (wp.color and 0x00FFFFFF) or 0x33000000
            canvas.drawRoundRect(r.left, r.top, r.right, r.top + r.height() * 0.5f, rad, rad, barPaint)
            ringPaint.color = if (sel) Palette.GOLD else 0x55FFFFFF
            ringPaint.strokeWidth = if (sel) h * 0.008f else h * 0.003f
            canvas.drawRoundRect(r, rad, rad, ringPaint)
            if (sel) {
                ringPaint.color = (wp.color and 0x00FFFFFF) or 0x66000000
                ringPaint.strokeWidth = h * 0.014f
                canvas.drawRoundRect(r, rad, rad, ringPaint)
            }
            // The astra's own icon.
            drawAstraIcon(canvas, wp, r.centerX(), r.top + r.height() * 0.36f, r.height() * 0.24f, out)
            textPaint.color = if (out) 0x66FFFFFF else Color.WHITE
            textPaint.textSize = r.height() * 0.16f
            canvas.drawText(wp.label, r.centerX(), r.top + r.height() * 0.7f, textPaint)
            // Ammo badge.
            textPaint.textSize = r.height() * 0.15f
            textPaint.color = if (out) 0xFFFF6A6A.toInt() else 0xCCFFFFFF.toInt()
            canvas.drawText(if (wp.unlimited) "∞" else "x${ammoOf(wp)}", r.centerX(), r.bottom - r.height() * 0.1f, textPaint)
        }

        // Selected astra name + mythic description (Mahabharata / Ramayana lore).
        val selTop = weaponRects[0].top
        val selCx = (weaponRects.first().left + weaponRects.last().right) / 2f
        textPaint.color = selected.color; textPaint.textSize = h * 0.036f
        val dmgTxt = "DMG ${(selected.damage * bowMult).toInt()}" + if (selected.blastFrac > 0f) "  •  BLAST" else ""
        canvas.drawText("${selected.full}   —   $dmgTxt", selCx, selTop - h * 0.055f, textPaint)
        textPaint.color = 0xCCFFFFFF.toInt(); textPaint.textSize = h * 0.024f
        canvas.drawText(selected.desc, selCx, selTop - h * 0.02f, textPaint)

        if (state == State.PLAYER_AIM && !aiming) {
            textPaint.color = 0xAAFFF0C0.toInt(); textPaint.textSize = h * 0.03f
            canvas.drawText("YOUR TURN — drag from Ram to aim, release to fire", w * 0.5f, h * 0.30f, textPaint)
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

    /** A distinct silhouette icon for each astra, for the selector tiles. */
    private fun drawAstraIcon(canvas: Canvas, wp: Weapon, cx: Float, cy: Float, r: Float, dim: Boolean) {
        val c = if (dim) 0x66FFFFFF.toInt() else wp.color
        barPaint.color = c; ringPaint.color = c; ringPaint.strokeCap = Paint.Cap.ROUND
        path.reset()
        when (wp) {
            Weapon.BAAN -> {
                // Diagonal arrow.
                ringPaint.strokeWidth = r * 0.28f
                canvas.drawLine(cx - r, cy + r, cx + r * 0.7f, cy - r * 0.7f, ringPaint)
                path.moveTo(cx + r, cy - r); path.lineTo(cx + r * 0.2f, cy - r * 0.75f); path.lineTo(cx + r * 0.75f, cy - r * 0.2f); path.close()
                canvas.drawPath(path, barPaint)
            }
            Weapon.AGNI -> {
                // Flame teardrop.
                path.moveTo(cx, cy - r * 1.1f)
                path.cubicTo(cx + r * 0.9f, cy - r * 0.2f, cx + r * 0.6f, cy + r, cx, cy + r)
                path.cubicTo(cx - r * 0.6f, cy + r, cx - r * 0.9f, cy - r * 0.2f, cx, cy - r * 1.1f)
                path.close(); canvas.drawPath(path, barPaint)
                barPaint.color = if (dim) 0x66FFFFFF.toInt() else 0xFFFFE07A.toInt()
                canvas.drawCircle(cx, cy + r * 0.25f, r * 0.4f, barPaint)
            }
            Weapon.NAGA -> {
                // Coiled serpent (S-curve) + head.
                ringPaint.strokeWidth = r * 0.34f
                path.moveTo(cx - r * 0.8f, cy + r * 0.9f)
                path.cubicTo(cx + r * 1.2f, cy + r * 0.4f, cx - r * 1.2f, cy - r * 0.4f, cx + r * 0.7f, cy - r * 0.9f)
                canvas.drawPath(path, strokePathPaint(ringPaint))
                canvas.drawCircle(cx + r * 0.7f, cy - r * 0.9f, r * 0.34f, barPaint)
            }
            Weapon.GADA -> {
                // Spiked mace with handle.
                ringPaint.strokeWidth = r * 0.24f
                canvas.drawLine(cx - r * 0.7f, cy + r, cx + r * 0.2f, cy - r * 0.1f, ringPaint)
                val hx = cx + r * 0.4f; val hy = cy - r * 0.35f
                for (a in 0 until 8) { val an = a * (Math.PI.toFloat() / 4f); canvas.drawCircle(hx + cos(an) * r * 0.65f, hy + sin(an) * r * 0.65f, r * 0.2f, barPaint) }
                canvas.drawCircle(hx, hy, r * 0.55f, barPaint)
            }
            Weapon.BRAHMA -> {
                // Radiant sun orb.
                ringPaint.strokeWidth = r * 0.16f
                for (a in 0 until 8) { val an = a * (Math.PI.toFloat() / 4f); canvas.drawLine(cx + cos(an) * r * 0.7f, cy + sin(an) * r * 0.7f, cx + cos(an) * r * 1.2f, cy + sin(an) * r * 1.2f, ringPaint) }
                canvas.drawCircle(cx, cy, r * 0.6f, barPaint)
                barPaint.color = if (dim) 0x66FFFFFF.toInt() else Color.WHITE
                canvas.drawCircle(cx, cy, r * 0.28f, barPaint)
            }
        }
    }

    private fun strokePathPaint(src: Paint): Paint { src.style = Paint.Style.STROKE; return src }

    private fun drawHelper(canvas: Canvas, r: RectF, name: String, used: Boolean, color: Int) {
        barPaint.color = if (used) 0x33202020 else (color and 0x00FFFFFF) or 0xAA000000.toInt()
        canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, barPaint)
        ringPaint.color = if (used) 0x33FFFFFF else color; ringPaint.strokeWidth = h * 0.005f
        canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, ringPaint)
        textPaint.color = if (used) 0x66FFFFFF else Color.WHITE; textPaint.textSize = r.height() * 0.2f
        canvas.drawText(name, r.centerX(), r.centerY() - r.height() * 0.02f, textPaint)
        textPaint.textSize = r.height() * 0.16f
        textPaint.color = if (used) 0x55FFFFFF else Palette.GOLD
        canvas.drawText(if (used) "used" else "SUMMON", r.centerX(), r.centerY() + r.height() * 0.26f, textPaint)
    }

    private fun drawQuiz(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        val q = question ?: return
        val cx = w / 2f
        textPaint.color = Palette.GOLD; textPaint.textSize = h * 0.05f
        canvas.drawText("GYAAN DWAR", cx, h * 0.14f, textPaint)
        textPaint.color = 0xAAFFFFFF.toInt(); textPaint.textSize = h * 0.026f
        canvas.drawText("Answer to earn punya points + a bonus astra", cx, h * 0.19f, textPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.036f
        canvas.drawText(q.text, cx, h * 0.30f, textPaint)

        for (i in quizRects.indices) {
            val r = quizRects[i]
            val fill = when {
                quizPicked < 0 -> 0xAA202832.toInt()
                i == q.answer -> 0xCC2E7D32.toInt()
                i == quizPicked -> 0xCC7D2E2E.toInt()
                else -> 0x66202832
            }
            barPaint.color = fill
            canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, barPaint)
            textPaint.color = Color.WHITE; textPaint.textSize = h * 0.032f
            canvas.drawText(q.options[i], r.centerX(), r.centerY() + h * 0.011f, textPaint)
        }
        if (quizPicked >= 0) {
            val ok = quizPicked == q.answer
            textPaint.color = if (ok) Palette.GRASS else Palette.DEMON; textPaint.textSize = h * 0.034f
            canvas.drawText(if (ok) "Correct! +${Prefs.QUIZ_REWARD} points, +1 Agni Baan" else "Not quite — the duel begins!",
                cx, h * 0.94f, textPaint)
        }
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
        // Campaign progress + next foe.
        val nextFoe = Enemy.ROSTER[prefs.stage.coerceIn(0, Enemy.FINAL)]
        textPaint.color = Palette.DEMON; textPaint.textSize = h * 0.034f
        canvas.drawText("STAGE ${prefs.stage + 1}/${Enemy.ROSTER.size}  —  NEXT: ${nextFoe.name.uppercase()}", cx, h * 0.375f, textPaint)
        textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.024f
        canvas.drawText(nextFoe.title, cx, h * 0.41f, textPaint)

        // Shop.
        textPaint.color = Palette.GOLD; textPaint.textSize = h * 0.026f
        canvas.drawText("PUNYA POINTS: ${prefs.points}   (tap to buy / equip)", cx, h * 0.445f, textPaint)
        drawUpgradeChip(canvas, rectArmour, "ARMOUR", "+50 HP", prefs.armourOwned, prefs.armourOn, Prefs.ARMOUR_COST)
        drawUpgradeChip(canvas, rectRath, "RATH", "+power/range", prefs.rathOwned, prefs.rathOn, Prefs.RATH_COST)
        drawBowChip(canvas, rectBow)

        val pulse = 0.5f + 0.5f * sin(bgPhase * 0.08f)
        barPaint.color = Palette.SAFFRON; barPaint.alpha = (200 + 55 * pulse).toInt()
        val pr = rectBegin.height() / 2f
        canvas.drawRoundRect(rectBegin, pr, pr, barPaint); barPaint.alpha = 255
        textPaint.color = 0xFF2A1206.toInt(); textPaint.textSize = h * 0.044f
        canvas.drawText("CHALLENGE ${nextFoe.name.uppercase()}", cx, rectBegin.centerY() + h * 0.015f, textPaint)

        textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.03f
        canvas.drawText("WINS ${prefs.wins}   •   RAVAN SLAIN ${prefs.ravanWins}×   •   BEST DMG ${prefs.bestScore}", cx, h * 0.90f, textPaint)
    }

    private fun drawUpgradeChip(canvas: Canvas, r: RectF, name: String, perk: String,
                                owned: Boolean, on: Boolean, cost: Int) {
        val avail = owned || prefs.points >= cost
        barPaint.color = when { on -> 0xCC3A2A12.toInt(); owned -> 0xAA202832.toInt(); avail -> 0xAA1A2230.toInt(); else -> 0x66101418.toInt() }
        canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, barPaint)
        ringPaint.color = if (on) Palette.GOLD else 0x44FFFFFF; ringPaint.strokeWidth = h * 0.005f
        canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, ringPaint)
        textPaint.color = if (avail) Palette.GOLD else 0x77FFFFFF.toInt(); textPaint.textSize = r.height() * 0.22f
        canvas.drawText(name, r.centerX(), r.top + r.height() * 0.30f, textPaint)
        textPaint.color = 0xCCFFFFFF.toInt(); textPaint.textSize = r.height() * 0.15f
        canvas.drawText(perk, r.centerX(), r.top + r.height() * 0.53f, textPaint)
        val status = when { on -> "EQUIPPED"; owned -> "tap to equip"; avail -> "BUY $cost pts"; else -> "$cost pts" }
        textPaint.color = if (on) Palette.GRASS else 0x99FFFFFF.toInt(); textPaint.textSize = r.height() * 0.15f
        canvas.drawText(status, r.centerX(), r.bottom - r.height() * 0.12f, textPaint)
    }

    private fun drawBowChip(canvas: Canvas, r: RectF) {
        val lvl = prefs.bowLevel; val maxed = lvl >= Prefs.MAX_BOW
        val avail = !maxed && prefs.points >= Prefs.BOW_COST
        barPaint.color = if (lvl > 0) 0xAA202832.toInt() else 0xAA1A2230.toInt()
        canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, barPaint)
        ringPaint.color = if (lvl > 0) Palette.GOLD else 0x44FFFFFF; ringPaint.strokeWidth = h * 0.005f
        canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, ringPaint)
        textPaint.color = Palette.GOLD; textPaint.textSize = r.height() * 0.22f
        canvas.drawText("BOW  Lv$lvl", r.centerX(), r.top + r.height() * 0.30f, textPaint)
        textPaint.color = 0xCCFFFFFF.toInt(); textPaint.textSize = r.height() * 0.15f
        canvas.drawText("+${(lvl * 20)}% dmg", r.centerX(), r.top + r.height() * 0.53f, textPaint)
        textPaint.color = if (maxed) Palette.GRASS else 0x99FFFFFF.toInt(); textPaint.textSize = r.height() * 0.15f
        canvas.drawText(if (maxed) "MAX" else "UP ${Prefs.BOW_COST} pts", r.centerX(), r.bottom - r.height() * 0.12f, textPaint)
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
        val foe = enemyDef.name.uppercase()
        val wasRavan = enemyDef.heads >= 10
        textPaint.color = if (playerWon) Palette.GOLD else Palette.DEMON
        textPaint.textSize = h * 0.09f
        val title = when {
            playerWon && wasRavan -> "RAVAN IS SLAIN!"
            playerWon -> "$foe DEFEATED!"
            else -> "RAM HAS FALLEN"
        }
        canvas.drawText(title, cx, h * 0.28f, textPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = h * 0.04f
        canvas.drawText("Rounds $round   •   Damage $damageDealt   •   +${if (playerWon) Prefs.WIN_REWARD else 0} pts", cx, h * 0.38f, textPaint)
        textPaint.color = 0x99FFFFFF.toInt(); textPaint.textSize = h * 0.03f
        val sub = if (playerWon) {
            if (wasRavan) "Lanka is free! A new, harder campaign awaits."
            else "Next foe: ${Enemy.ROSTER[prefs.stage].name.uppercase()}"
        } else "Tap FIGHT AGAIN to challenge $foe once more"
        canvas.drawText(sub, cx, h * 0.45f, textPaint)

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

    companion object {
        private const val FX_BAAN = 0
        private const val FX_AGNI = 1
        private const val FX_NAGA = 2
        private const val FX_GADA = 3
        private const val FX_BRAHMA = 4
        private const val FX_HANUMAN = 5
        private const val FX_LAKSHMAN = 6
        private const val FX_CAST = 7
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
