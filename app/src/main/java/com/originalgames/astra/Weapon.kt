package com.originalgames.astra

/**
 * A selectable weapon in the artillery duel. Each has its own damage, blast
 * radius, "weight" (gravity scale — heavier weapons arc more), projectile count
 * and per-battle ammo (BAAN is unlimited). This is the "different weapons"
 * layer of the Pocket-Tanks-style loadout.
 */
enum class Weapon(
    val label: String,
    val shots: Int,          // projectiles launched per fire (spread)
    val damage: Float,
    val blastFrac: Float,    // blast radius as a fraction of screen width (0 = direct hit)
    val gravityScale: Float, // >1 = heavier / more arc
    val ammo: Int,           // -1 = unlimited
    val color: Int
) {
    BAAN     ("Baan",       1, 15f, 0.030f, 1.0f, -1, 0xFFFFD24A.toInt()),
    AGNI     ("Agni Baan",  1, 24f, 0.070f, 1.0f, 6, 0xFFFF6A1E.toInt()),
    TRIBAAN  ("Tri-Baan",   3, 12f, 0.030f, 1.0f, 4, 0xFF34E0C8.toInt()),
    GADA     ("Gada",       1, 34f, 0.055f, 1.7f, 3, 0xFFB07A3A.toInt()),
    BRAHMA   ("Brahmastra", 1, 70f, 0.110f, 0.7f, 1, 0xFFFFE14A.toInt());

    val unlimited: Boolean get() = ammo < 0
}
