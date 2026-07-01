package com.originalgames.dhishoom

/**
 * The complete control state of one fighter for a single frame, packed into a
 * bitmask. The same representation is sampled from on-screen buttons, produced
 * by the bot, and sent across the network — so local, CPU and remote inputs are
 * all interchangeable.
 */
object Btn {
    const val LEFT = 1 shl 0
    const val RIGHT = 1 shl 1
    const val JUMP = 1 shl 2
    const val PUNCH = 1 shl 3
    const val KICK = 1 shl 4
    const val BLOCK = 1 shl 5
    const val SPECIAL = 1 shl 6

    fun has(mask: Int, btn: Int) = (mask and btn) != 0
}
