package com.originalgames.astra

/**
 * A selectable astra in the artillery duel. Names and lore are drawn from the
 * divine weapons of the Ramayana and Mahabharata; each has its own damage,
 * blast radius, "weight" (gravity scale — heavier astras arc more), projectile
 * count and per-battle ammo (the Sharanga arrow is unlimited).
 */
enum class Weapon(
    val label: String,       // short label for the selector button
    val full: String,        // full astra name
    val desc: String,        // mythic one-liner shown when selected
    val shots: Int,
    val damage: Float,
    val blastFrac: Float,
    val gravityScale: Float,
    val ammo: Int,           // -1 = unlimited
    val color: Int
) {
    BAAN("Baan", "Sharanga Baan",
        "Swift arrow of Vishnu's bow — endless quiver.",
        1, 16f, 0.030f, 1.0f, -1, 0xFFFFD24A.toInt()),
    AGNI("Agni", "Agneyastra",
        "Astra of Agni — erupts into a wall of flame.",
        1, 24f, 0.070f, 1.0f, 6, 0xFFFF6A1E.toInt()),
    NAGA("Naga", "Nagastra",
        "Serpent astra — strikes as a spread of three.",
        3, 12f, 0.030f, 1.0f, 4, 0xFF49E07A.toInt()),
    GADA("Gada", "Kaumodaki",
        "Vishnu's mighty mace — heavy arc, huge blast.",
        1, 34f, 0.055f, 1.7f, 3, 0xFFB07A3A.toInt()),
    BRAHMA("Brahma", "Brahmastra",
        "The ultimate astra of Brahma. Only one per duel.",
        1, 70f, 0.110f, 0.7f, 1, 0xFFFFE14A.toInt());

    val unlimited: Boolean get() = ammo < 0
}
