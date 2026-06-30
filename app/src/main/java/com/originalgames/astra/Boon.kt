package com.originalgames.astra

import kotlin.random.Random

/**
 * The run-defining stats of Ram's bow. Boons mutate this loadout, so each run
 * grows its own "build" — the Archero-style progression that turns a simple
 * core loop into a compulsive one.
 */
class Loadout {
    var fireInterval = 22       // frames between volleys (lower = faster)
    var arrowsPerShot = 1
    var spread = 0f             // total fan angle (radians) when >1 arrow
    var arrowDamage = 5f
    var pierce = false
    var critChance = 0f         // 0..1 chance of a 2x arrow
    var astraChargeMult = 1f

    fun reset() {
        fireInterval = 22
        arrowsPerShot = 1
        spread = 0f
        arrowDamage = 5f
        pierce = false
        critChance = 0f
        astraChargeMult = 1f
    }
}

/**
 * A "Vardaan" (boon) offered between heads. Each is a one-line, readable upgrade
 * — the offer screen is a key share-and-retention moment ("look at my build").
 */
class Boon(
    val name: String,
    val desc: String,
    val color: Int,
    val apply: (Loadout, Ram) -> Unit
) {
    companion object {
        val ALL: List<Boon> = listOf(
            Boon("TWIN ARROWS", "+1 arrow per shot", 0xFF34E0C8.toInt()) { l, _ ->
                l.arrowsPerShot += 1
                if (l.spread < 0.16f) l.spread = 0.16f
            },
            Boon("RAPID DRAW", "Fire much faster", 0xFFFFC23A.toInt()) { l, _ ->
                l.fireInterval = (l.fireInterval * 0.8f).toInt().coerceAtLeast(6)
            },
            Boon("VAJRA TIP", "+35% arrow damage", 0xFFFF6A3D.toInt()) { l, _ ->
                l.arrowDamage *= 1.35f
            },
            Boon("WIDE VOLLEY", "Wider arrow spread", 0xFF9B7BFF.toInt()) { l, _ ->
                l.spread += 0.14f
                if (l.arrowsPerShot < 2) l.arrowsPerShot = 2
            },
            Boon("KAVACH", "+1 max life (healed)", 0xFF49E07A.toInt()) { _, r ->
                r.maxLives += 1; r.lives = (r.lives + 1).coerceAtMost(r.maxLives)
            },
            Boon("PIERCING", "Arrows pierce through", 0xFFE0E6EE.toInt()) { l, _ ->
                if (l.pierce) l.arrowDamage *= 1.25f else l.pierce = true
            },
            Boon("ASTRA SURGE", "+40% astra charge", 0xFFFFE14A.toInt()) { l, _ ->
                l.astraChargeMult *= 1.4f
            },
            Boon("CRIT BLESSING", "+20% double-damage", 0xFFFF4D7A.toInt()) { l, _ ->
                l.critChance = (l.critChance + 0.2f).coerceAtMost(0.8f)
            }
        )

        /** Three distinct boons for the choice screen, drawn from [rng]. */
        fun offer(rng: Random): List<Boon> = ALL.shuffled(rng).take(3)
    }
}
