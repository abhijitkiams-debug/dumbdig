package com.originalgames.flipside

/**
 * A cosmetic orb skin. [down] is the orb colour while gravity pulls down and
 * [up] while it pulls up, so flipping visibly recolours the orb. A skin unlocks
 * once the player's lifetime gem count reaches [cost].
 */
data class Skin(
    val name: String,
    val down: Int,
    val up: Int,
    val cost: Int
)

object Skins {
    // Colours are full ARGB (0xFF alpha). Ordered by unlock cost.
    val all: List<Skin> = listOf(
        Skin("CLASSIC", 0xFF22E0C8.toInt(), 0xFFFF4D7A.toInt(), 0),
        Skin("EMBER", 0xFFFFB347.toInt(), 0xFFFF5E5B.toInt(), 25),
        Skin("VAPOR", 0xFF9B5DE5.toInt(), 0xFF00BBF9.toInt(), 75),
        Skin("TOXIC", 0xFFB8FF3B.toInt(), 0xFF39FF14.toInt(), 150),
        Skin("SOLAR", 0xFFFFD25A.toInt(), 0xFFFF8C42.toInt(), 300),
        Skin("MONO", 0xFFFFFFFF.toInt(), 0xFFAAAAAA.toInt(), 500)
    )

    fun isUnlocked(index: Int, lifetimeGems: Int): Boolean =
        all[index].cost <= lifetimeGems

    fun clampIndex(index: Int): Int = ((index % all.size) + all.size) % all.size
}
