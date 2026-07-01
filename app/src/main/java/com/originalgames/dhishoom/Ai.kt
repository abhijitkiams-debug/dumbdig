package com.originalgames.dhishoom

import kotlin.random.Random

/**
 * A compact behaviour controller that drives a [Fighter] by emitting the same
 * button bitmask a human would. It reads the public state of both fighters and
 * makes a fresh decision each frame, throttled so it doesn't mash. Three
 * difficulty presets tune reaction speed, aggression and defence.
 */
class Ai(private val difficulty: Difficulty) {

    enum class Difficulty(
        val reactionTicks: Int,   // think delay between committed attacks
        val aggression: Float,    // chance to attack when in range
        val blockChance: Float,   // chance to block an incoming strike
        val specialChance: Float, // chance to fire a ready super
        val jumpChance: Float
    ) {
        EASY(28, 0.45f, 0.18f, 0.25f, 0.012f),
        NORMAL(16, 0.7f, 0.45f, 0.6f, 0.02f),
        HARD(8, 0.9f, 0.72f, 0.85f, 0.03f)
    }

    private var think = 0
    private var holdButton = 0
    private var holdTicks = 0

    fun nextButtons(self: Fighter, opp: Fighter): Int {
        if (think > 0) think--
        if (holdTicks > 0) { holdTicks--; if (holdTicks == 0) holdButton = 0 }

        // Can't act meaningfully while stunned or attacking — wait it out.
        when (self.state) {
            Fighter.S.HIT, Fighter.S.DOWN, Fighter.S.KO,
            Fighter.S.PUNCH, Fighter.S.KICK, Fighter.S.SPECIAL -> return 0
            else -> {}
        }

        val dist = self.distanceTo(opp)
        val range = self.height * 0.72f

        // Reactive defence: block an attack that's about to land.
        val oppAttacking = opp.state == Fighter.S.PUNCH ||
            opp.state == Fighter.S.KICK || opp.state == Fighter.S.SPECIAL
        if (oppAttacking && dist < range * 1.25f && Random.nextFloat() < difficulty.blockChance) {
            return Btn.BLOCK
        }

        // Honour a short attack commitment so a chosen move actually fires.
        if (holdButton != 0) return holdButton

        // Close the distance.
        if (dist > range) {
            var b = if (opp.x > self.x) Btn.RIGHT else Btn.LEFT
            if (Random.nextFloat() < difficulty.jumpChance) b = b or Btn.JUMP
            return b
        }

        // In range: decide whether to strike.
        if (think == 0 && Random.nextFloat() < difficulty.aggression) {
            think = difficulty.reactionTicks + Random.nextInt(6)
            val pick = when {
                self.meterFull && Random.nextFloat() < difficulty.specialChance -> Btn.SPECIAL
                Random.nextFloat() < 0.5f -> Btn.PUNCH
                else -> Btn.KICK
            }
            holdButton = pick
            holdTicks = 3
            return pick
        }

        // Otherwise jockey for position / occasionally guard.
        return when {
            Random.nextFloat() < 0.04f -> Btn.BLOCK
            Random.nextFloat() < 0.2f -> if (opp.x > self.x) Btn.RIGHT else Btn.LEFT
            else -> 0
        }
    }

    fun reset() { think = 0; holdButton = 0; holdTicks = 0 }
}
