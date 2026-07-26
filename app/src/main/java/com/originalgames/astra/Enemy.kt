package com.originalgames.astra

/**
 * The campaign ladder of foes Ram must defeat, in order, culminating in Ravan.
 * Each has its own health, aim accuracy (higher = deadlier), size, skin and a
 * silhouette hint (number of heads / crown) so the Fighter can draw a variant.
 */
data class Enemy(
    val name: String,
    val title: String,
    val hp: Float,
    val accuracy: Float,   // higher = more accurate return fire
    val skin: Int,
    val heads: Int,
    val scale: Float,
    val crown: Boolean
) {
    companion object {
        val ROSTER: List<Enemy> = listOf(
            Enemy("Tadaka", "the demoness of the forest", 55f, 0.7f, 0xFF6E8C3A.toInt(), 1, 0.125f, false),
            Enemy("Vali", "the mighty vanara king", 80f, 0.95f, 0xFF9A6B34.toInt(), 1, 0.13f, true),
            Enemy("Kumbhakarna", "the colossal sleeper", 110f, 0.6f, 0xFFB08A5A.toInt(), 1, 0.18f, false),
            Enemy("Meghnad", "Indrajit, master of maya", 95f, 1.25f, 0xFF7A4AA0.toInt(), 1, 0.12f, true),
            Enemy("Ravan", "the ten-headed Demon-King", 120f, 1.1f, 0xFF4A2036.toInt(), 10, 0.145f, true)
        )
        val FINAL = ROSTER.size - 1
    }
}
