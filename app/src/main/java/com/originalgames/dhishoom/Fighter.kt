package com.originalgames.dhishoom

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One combatant: physics, a small fighting-game state machine, hit/hurt boxes,
 * and fully procedural humanoid rendering. No sprites — the figure is drawn from
 * circles and round-capped limbs whose poses are computed from the current
 * state and its frame timer.
 *
 * All sizes scale off [height] (the figure's standing height in pixels) so the
 * fighter looks identical on any screen. The host drives a fighter with
 * [update]; a networked client instead overwrites the public fields directly
 * from a [Snapshot] and only calls [draw].
 */
class Fighter(
    val archetype: FighterArchetype,
    var height: Float,
    var groundY: Float
) {
    enum class S { IDLE, WALK, JUMP, BLOCK, PUNCH, KICK, SPECIAL, HIT, DOWN, KO, WIN }

    // --- Public state (also the wire format for networked clients) ---
    var x = 0f
    var facing = 1            // +1 faces right, -1 faces left
    var y = groundY           // feet baseline
    var vx = 0f
    var vy = 0f
    var state = S.IDLE
    var stateTime = 0
    var hp = 100f
    var meter = 0f

    val maxHp = 100f * archetype.healthMul
    private var onGround = true
    private var attackHasHit = false
    private var startedAttack = 0   // 1=punch 2=kick 3=special, consumed for SFX

    // --- Move frame data (ticks @ 60 fps). Damage is the base before power. ---
    private class Move(
        val startup: Int, val active: Int, val recovery: Int,
        val damage: Float, val knockback: Float, val hitstun: Int,
        val knockdown: Boolean, val meterGain: Float
    ) {
        val total get() = startup + active + recovery
    }

    private val punch = Move(5, 4, 9, 7f, 0.30f, 12, false, 14f)
    private val kick = Move(9, 5, 16, 13f, 0.55f, 20, false, 18f)
    private val special = Move(8, 8, 22, 26f, 1.1f, 40, true, 0f)

    private fun moveFor(s: S): Move? = when (s) {
        S.PUNCH -> punch
        S.KICK -> kick
        S.SPECIAL -> special
        else -> null
    }

    // --- Derived metrics ---
    private val walkSpeed get() = height * 0.040f * archetype.speedMul
    private val jumpImpulse get() = height * 0.165f
    private val gravity get() = height * 0.013f
    private val bodyHalfWidth get() = height * 0.20f

    // --- Paints (allocated once) ---
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val trimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // ----------------------------------------------------------------------

    fun reset(startX: Float, faceDir: Int) {
        x = startX; facing = faceDir
        y = groundY; vx = 0f; vy = 0f
        state = S.IDLE; stateTime = 0
        hp = maxHp; meter = 0f
        onGround = true; attackHasHit = false; startedAttack = 0
    }

    fun resetForRound(startX: Float, faceDir: Int) {
        x = startX; facing = faceDir
        y = groundY; vx = 0f; vy = 0f
        state = S.IDLE; stateTime = 0
        hp = maxHp
        // Carry a little meter between rounds so comebacks stay possible.
        meter = (meter * 0.5f)
        onGround = true; attackHasHit = false; startedAttack = 0
    }

    val isAlive get() = hp > 0f
    val meterFull get() = meter >= METER_MAX

    /** True when the fighter can begin a new action this frame. */
    private fun actionable(): Boolean =
        state == S.IDLE || state == S.WALK || (state == S.JUMP && onGround)

    /** Fighters in these states can't be combo-extended / are immune. */
    fun hittable(): Boolean = when (state) {
        S.DOWN, S.KO, S.WIN -> false
        else -> true
    }

    /** Free / not in hitstun — used to know when a combo has ended. */
    fun isNeutral(): Boolean =
        state == S.IDLE || state == S.WALK || state == S.JUMP || state == S.BLOCK

    /** True while being comboed (in hitstun or knocked down). */
    fun isStunned(): Boolean = state == S.HIT || state == S.DOWN

    /** Brief invulnerability: rising into a super, and while knocked down. */
    private fun invulnerable(): Boolean {
        if (state == S.DOWN || state == S.KO || state == S.WIN) return true
        if (state == S.SPECIAL && stateTime < special.startup) return true
        return false
    }

    /** Public guard for the hit resolver: can this fighter be struck right now? */
    fun canBeHit(): Boolean = hittable() && !invulnerable()

    fun setWinPose() {
        if (state != S.KO) { state = S.WIN; stateTime = 0; vx = 0f }
    }

    fun consumeStartedAttack(): Int {
        val s = startedAttack; startedAttack = 0; return s
    }

    // ----------------------------------------------------------------------
    // Simulation (host only)
    // ----------------------------------------------------------------------

    fun update(buttons: Int, opponentX: Float, leftBound: Float, rightBound: Float) {
        stateTime++

        // Face the opponent whenever we're free to turn.
        if (actionable() && state != S.JUMP) {
            facing = if (opponentX >= x) 1 else -1
        }

        when (state) {
            S.IDLE, S.WALK -> grounded(buttons)
            S.JUMP -> airborne(buttons)
            S.BLOCK -> {
                if (!Btn.has(buttons, Btn.BLOCK)) { state = S.IDLE; stateTime = 0 }
                vx = 0f
            }
            S.PUNCH, S.KICK, S.SPECIAL -> {
                vx *= 0.8f
                val m = moveFor(state)!!
                if (stateTime >= m.total) { state = S.IDLE; stateTime = 0; attackHasHit = false }
            }
            S.HIT -> {
                vx *= 0.9f
                if (stateTime >= HIT_STUN_DEFAULT_CAP || (stateTime >= currentHitstun)) {
                    state = S.IDLE; stateTime = 0
                }
            }
            S.DOWN -> {
                vx *= 0.85f
                if (stateTime >= DOWN_TICKS) { state = S.IDLE; stateTime = 0 }
            }
            S.KO -> vx *= 0.85f
            S.WIN -> vx = 0f
        }

        integrate(leftBound, rightBound)
    }

    private var currentHitstun = 0

    private fun grounded(buttons: Int) {
        when {
            Btn.has(buttons, Btn.SPECIAL) && meterFull -> startAttack(S.SPECIAL)
            Btn.has(buttons, Btn.KICK) -> startAttack(S.KICK)
            Btn.has(buttons, Btn.PUNCH) -> startAttack(S.PUNCH)
            Btn.has(buttons, Btn.BLOCK) -> { state = S.BLOCK; stateTime = 0; vx = 0f }
            Btn.has(buttons, Btn.JUMP) -> {
                state = S.JUMP; stateTime = 0; onGround = false; vy = -jumpImpulse
                applyWalk(buttons)
            }
            else -> applyWalk(buttons)
        }
    }

    private fun applyWalk(buttons: Int) {
        vx = when {
            Btn.has(buttons, Btn.LEFT) -> -walkSpeed
            Btn.has(buttons, Btn.RIGHT) -> walkSpeed
            else -> 0f
        }
        if (state != S.JUMP) {
            state = if (vx != 0f) S.WALK else S.IDLE
        }
    }

    private fun airborne(buttons: Int) {
        // Limited air drift; no air attacks (keeps the game readable).
        vx = when {
            Btn.has(buttons, Btn.LEFT) -> -walkSpeed * 0.7f
            Btn.has(buttons, Btn.RIGHT) -> walkSpeed * 0.7f
            else -> vx * 0.98f
        }
    }

    private fun startAttack(s: S) {
        state = s; stateTime = 0; attackHasHit = false; vx = 0f
        if (s == S.SPECIAL) meter = 0f
        startedAttack = when (s) { S.PUNCH -> 1; S.KICK -> 2; else -> 3 }
    }

    private fun integrate(leftBound: Float, rightBound: Float) {
        x += vx
        if (!onGround) {
            y += vy
            vy += gravity
            if (y >= groundY) {
                y = groundY; vy = 0f; onGround = true
                if (state == S.JUMP) { state = S.IDLE; stateTime = 0 }
            }
        }
        x = x.coerceIn(leftBound + bodyHalfWidth, rightBound - bodyHalfWidth)
    }

    // ----------------------------------------------------------------------
    // Hit / hurt boxes & damage
    // ----------------------------------------------------------------------

    fun hurtBox(out: RectF) {
        val top = if (state == S.BLOCK) y - height * 0.92f else y - height * 0.98f
        out.set(x - bodyHalfWidth, top, x + bodyHalfWidth, y)
    }

    /** True only on the active frames of an attack that hasn't connected yet. */
    fun canHit(): Boolean {
        val m = moveFor(state) ?: return false
        if (attackHasHit) return false
        return stateTime >= m.startup && stateTime < m.startup + m.active
    }

    fun attackBox(out: RectF): Boolean {
        val m = moveFor(state) ?: return false
        val (reach, top, bottom) = when (state) {
            S.PUNCH -> Triple(height * 0.58f, y - height * 0.80f, y - height * 0.58f)
            S.KICK -> Triple(height * 0.74f, y - height * 0.58f, y - height * 0.30f)
            else -> Triple(height * 0.88f, y - height * 0.82f, y - height * 0.30f)
        }
        val front = x + facing * reach
        if (facing > 0) out.set(x, top, front, bottom) else out.set(front, top, x, bottom)
        // Touch the move so the unused warning stays quiet and intent is clear.
        if (m.active <= 0) return false
        return true
    }

    fun markHit() { attackHasHit = true }

    fun currentMove(): Int = when (state) {  // for damage scaling lookups
        S.PUNCH -> 1; S.KICK -> 2; S.SPECIAL -> 3; else -> 0
    }

    enum class HitResult { BLOCKED, HIT, KO }

    /** Applies an incoming attack from [attacker]. Returns how it landed. */
    fun takeHit(attacker: Fighter): HitResult {
        if (invulnerable()) return HitResult.BLOCKED // treated as no-op upstream via guard
        val m = attacker.moveFor(attacker.state) ?: return HitResult.BLOCKED
        val dir = if (attacker.x <= x) 1 else -1
        val blocking = state == S.BLOCK && facingToward(attacker.x)

        if (blocking) {
            val chip = m.damage * attacker.archetype.powerMul * 0.12f
            hp = (hp - chip).coerceAtLeast(1f)            // chip never KOs
            vx = dir * m.knockback * height * 0.12f
            meter = (meter + 4f).coerceAtMost(METER_MAX)
            stateTime = 0                                  // refresh blockstun
            return HitResult.BLOCKED
        }

        val dmg = m.damage * attacker.archetype.powerMul
        hp -= dmg
        meter = (meter + 8f).coerceAtMost(METER_MAX)
        vx = dir * m.knockback * height * 0.16f
        facing = -dir

        return if (hp <= 0f) {
            hp = 0f
            state = S.KO; stateTime = 0
            vy = -height * 0.06f; onGround = false
            HitResult.KO
        } else if (m.knockdown) {
            state = S.DOWN; stateTime = 0
            vy = -height * 0.10f; onGround = false
            HitResult.HIT
        } else {
            state = S.HIT; stateTime = 0; currentHitstun = m.hitstun
            HitResult.HIT
        }
    }

    private fun facingToward(px: Float): Boolean =
        (px >= x && facing > 0) || (px < x && facing < 0)

    /** Where to spray hit sparks from (front of this fighter, mid height). */
    fun hitSparkX() = x + facing * bodyHalfWidth
    fun hitSparkY() = y - height * 0.55f

    // ----------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------

    fun draw(canvas: Canvas, flashHit: Boolean) {
        if (state == S.DOWN || state == S.KO) { drawDowned(canvas); return }

        val f = facing.toFloat()
        val H = height
        // A subtle idle bob / crouch.
        val crouch = if (state == S.BLOCK) H * 0.06f else 0f
        val bob = if (state == S.IDLE) sin(stateTime * 0.12f) * H * 0.01f else 0f
        val hipY = y - H * 0.46f + crouch + bob
        val shoulderY = y - H * 0.74f + crouch + bob
        val headCY = y - H * 0.88f + crouch + bob
        val headR = H * 0.11f

        // Extension factor for the active limb (0..1) over the move timeline.
        val ext = attackExtension()

        // Legs.
        limbPaint.color = darken(archetype.body, 0.75f)
        limbPaint.strokeWidth = H * 0.085f
        when (state) {
            S.WALK -> {
                val sw = sin(stateTime * 0.4f) * H * 0.10f
                leg(canvas, x, hipY, x + sw, y)
                leg(canvas, x, hipY, x - sw, y)
            }
            S.JUMP -> {
                leg(canvas, x, hipY, x + f * H * 0.06f, y - H * 0.10f)
                leg(canvas, x, hipY, x - f * H * 0.04f, y - H * 0.06f)
            }
            S.KICK -> {
                // Front leg thrusts forward on the active frames.
                val kx = x + f * (H * 0.10f + ext * H * 0.62f)
                val ky = y - H * 0.30f - ext * H * 0.05f
                leg(canvas, x, hipY, x - f * H * 0.06f, y)
                limbPaint.strokeWidth = H * 0.095f
                leg(canvas, x, hipY, kx, ky)
                limbPaint.strokeWidth = H * 0.085f
            }
            else -> {
                leg(canvas, x, hipY, x + f * H * 0.05f, y)
                leg(canvas, x, hipY, x - f * H * 0.07f, y)
            }
        }

        // Torso.
        bodyPaint.color = if (flashHit) Color.WHITE else archetype.body
        val tw = bodyHalfWidth
        canvas.drawRoundRect(
            x - tw, shoulderY, x + tw, hipY + H * 0.04f,
            tw * 0.6f, tw * 0.6f, bodyPaint
        )
        // Belt / trim.
        bodyPaint.color = if (flashHit) Color.WHITE else archetype.trim
        canvas.drawRoundRect(x - tw, hipY - H * 0.03f, x + tw, hipY + H * 0.04f, tw * 0.4f, tw * 0.4f, bodyPaint)

        // Arms.
        limbPaint.color = if (flashHit) Color.WHITE else archetype.body
        limbPaint.strokeWidth = H * 0.07f
        val sx = x + f * tw * 0.5f
        when (state) {
            S.PUNCH -> {
                val px = x + f * (tw + ext * H * 0.52f)
                val py = shoulderY + H * 0.06f
                arm(canvas, sx, shoulderY + H * 0.02f, px, py)
                arm(canvas, x - f * tw * 0.4f, shoulderY + H * 0.04f, x - f * tw * 0.3f, shoulderY + H * 0.20f)
                fist(canvas, px, py, H * 0.05f, if (flashHit) Color.WHITE else archetype.trim)
            }
            S.SPECIAL -> {
                val px = x + f * (tw + ext * H * 0.66f)
                val py = shoulderY + H * 0.10f
                arm(canvas, sx, shoulderY + H * 0.02f, px, py)
                arm(canvas, sx, shoulderY + H * 0.05f, px, py + H * 0.10f)
                fist(canvas, px, py + H * 0.05f, H * 0.07f, 0xFFFFFFFF.toInt())
            }
            S.BLOCK -> {
                val gx = x + f * tw * 0.9f
                arm(canvas, sx, shoulderY + H * 0.03f, gx, shoulderY - H * 0.02f)
                arm(canvas, x - f * tw * 0.2f, shoulderY + H * 0.06f, gx, shoulderY + H * 0.12f)
            }
            S.WIN -> {
                arm(canvas, sx, shoulderY, x + f * tw, shoulderY - H * 0.22f)
                arm(canvas, x - f * tw * 0.5f, shoulderY, x - f * tw, shoulderY - H * 0.22f)
            }
            S.WALK -> {
                val sw = sin(stateTime * 0.4f) * H * 0.10f
                arm(canvas, sx, shoulderY + H * 0.02f, x + f * tw * 0.5f - sw, shoulderY + H * 0.22f)
                arm(canvas, x - f * tw * 0.4f, shoulderY + H * 0.02f, x - f * tw * 0.5f + sw, shoulderY + H * 0.22f)
            }
            else -> {
                arm(canvas, sx, shoulderY + H * 0.02f, x + f * tw * 0.7f, shoulderY + H * 0.20f)
                arm(canvas, x - f * tw * 0.4f, shoulderY + H * 0.02f, x - f * tw * 0.6f, shoulderY + H * 0.20f)
            }
        }

        // Head + face.
        bodyPaint.color = if (flashHit) Color.WHITE else archetype.body
        canvas.drawCircle(x, headCY, headR, bodyPaint)
        // Headband trim.
        trimPaint.color = if (flashHit) Color.WHITE else archetype.trim
        trimPaint.strokeWidth = headR * 0.5f
        canvas.drawLine(x - headR, headCY - headR * 0.35f, x + headR, headCY - headR * 0.35f, trimPaint)
        // Eye facing forward.
        canvas.drawCircle(x + f * headR * 0.4f, headCY, headR * 0.18f, eyePaint)

        if (state == S.SPECIAL) drawSpecialGlow(canvas, headCY)
    }

    private fun attackExtension(): Float {
        val m = moveFor(state) ?: return 0f
        return when {
            stateTime < m.startup -> stateTime / m.startup.toFloat()
            stateTime < m.startup + m.active -> 1f
            else -> {
                val r = (stateTime - m.startup - m.active) / m.recovery.toFloat()
                (1f - r).coerceIn(0f, 1f)
            }
        }
    }

    private fun drawSpecialGlow(canvas: Canvas, headCY: Float) {
        glowPaint.color = (0x66 shl 24) or (0xFFD25A.toInt() and 0x00FFFFFF)
        canvas.drawCircle(x, (headCY + y) / 2f, height * 0.4f, glowPaint)
    }

    private fun drawDowned(canvas: Canvas) {
        val H = height
        val cx = x - facing * H * 0.1f
        bodyPaint.color = if (state == S.KO) darken(archetype.body, 0.7f) else archetype.body
        // Slumped body along the ground.
        canvas.drawRoundRect(cx - H * 0.30f, y - H * 0.18f, cx + H * 0.30f, y, H * 0.09f, H * 0.09f, bodyPaint)
        bodyPaint.color = archetype.trim
        canvas.drawCircle(cx - facing * H * 0.34f, y - H * 0.12f, H * 0.11f, bodyPaint)
        if (state == S.KO) {
            // Little "stars" so a KO reads instantly.
            eyePaint.color = 0xFFFFD25A.toInt()
            val t = stateTime * 0.2f
            for (i in 0 until 3) {
                val a = t + i * 2.1f
                canvas.drawCircle(
                    cx - facing * H * 0.34f + cos(a) * H * 0.18f,
                    y - H * 0.32f + sin(a) * H * 0.05f,
                    H * 0.025f, eyePaint
                )
            }
            eyePaint.color = Color.WHITE
        }
    }

    private fun leg(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) =
        canvas.drawLine(x1, y1, x2, y2, limbPaint)

    private fun arm(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) =
        canvas.drawLine(x1, y1, x2, y2, limbPaint)

    private fun fist(canvas: Canvas, fx: Float, fy: Float, r: Float, color: Int) {
        bodyPaint.color = color
        canvas.drawCircle(fx, fy, r, bodyPaint)
    }

    private fun darken(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    /** Meter the current attack awards its user on a clean hit. */
    fun currentMoveMeterGain(): Float = moveFor(state)?.meterGain ?: 0f

    fun addMeter(amount: Float) { meter = (meter + amount).coerceAtMost(METER_MAX) }

    /** Overwrite render state from a networked snapshot (client side). */
    fun applyNet(nx: Float, ny: Float, nfacing: Int, stateOrdinal: Int, nstateTime: Int, nhp: Float, nmeter: Float) {
        x = nx; y = ny; facing = if (nfacing >= 0) 1 else -1
        state = S.values().getOrElse(stateOrdinal) { S.IDLE }
        stateTime = nstateTime; hp = nhp; meter = nmeter
    }

    /** Distance between two fighters' centres (used by the bot). */
    fun distanceTo(other: Fighter) = abs(x - other.x)

    companion object {
        const val METER_MAX = 100f
        private const val DOWN_TICKS = 52
        private const val HIT_STUN_DEFAULT_CAP = 60
    }
}
