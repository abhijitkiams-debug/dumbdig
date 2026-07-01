package com.originalgames.dhishoom

/**
 * The playable roster. Each fighter is an original character with a name, a two
 * colour palette (body + trim), and a small set of stat multipliers that make
 * them feel distinct without changing the control scheme. A fighter unlocks once
 * the player's career wins reach [unlockWins].
 *
 * The names and personas are invented for this project; any resemblance to the
 * akhada (wrestling-pit) flavour is theme only — no third-party characters,
 * likenesses, or assets are used.
 */
data class FighterArchetype(
    val name: String,
    val tagline: String,
    val body: Int,
    val trim: Int,
    val speedMul: Float,
    val powerMul: Float,
    val healthMul: Float,
    val unlockWins: Int
)

object Roster {
    val all: List<FighterArchetype> = listOf(
        FighterArchetype(
            name = "RANI",
            tagline = "Speed of the storm",
            body = 0xFF22E0C8.toInt(), trim = 0xFF0E5A52.toInt(),
            speedMul = 1.18f, powerMul = 0.9f, healthMul = 0.95f, unlockWins = 0
        ),
        FighterArchetype(
            name = "BHIM",
            tagline = "The mountain hits back",
            body = 0xFFFF8C42.toInt(), trim = 0xFF7A3A12.toInt(),
            speedMul = 0.86f, powerMul = 1.25f, healthMul = 1.15f, unlockWins = 0
        ),
        FighterArchetype(
            name = "VEER",
            tagline = "All-rounder of the akhada",
            body = 0xFFFFD25A.toInt(), trim = 0xFF7A5E10.toInt(),
            speedMul = 1.0f, powerMul = 1.0f, healthMul = 1.0f, unlockWins = 0
        ),
        FighterArchetype(
            name = "NAAG",
            tagline = "Strikes you never see",
            body = 0xFF9B5DE5.toInt(), trim = 0xFF3C2363.toInt(),
            speedMul = 1.1f, powerMul = 1.08f, healthMul = 0.9f, unlockWins = 3
        ),
        FighterArchetype(
            name = "GARUD",
            tagline = "Wings, then ruin",
            body = 0xFFFF4D5B.toInt(), trim = 0xFF7A1620.toInt(),
            speedMul = 1.05f, powerMul = 1.15f, healthMul = 1.0f, unlockWins = 8
        ),
        FighterArchetype(
            name = "TITAN",
            tagline = "Unmovable, unforgiving",
            body = 0xFFE6E6E6.toInt(), trim = 0xFF555555.toInt(),
            speedMul = 0.9f, powerMul = 1.3f, healthMul = 1.2f, unlockWins = 15
        )
    )

    fun isUnlocked(index: Int, careerWins: Int): Boolean =
        all[index].unlockWins <= careerWins

    fun clampIndex(index: Int): Int = ((index % all.size) + all.size) % all.size
}
