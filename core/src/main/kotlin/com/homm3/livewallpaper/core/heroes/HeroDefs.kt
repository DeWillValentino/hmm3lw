package com.homm3.livewallpaper.core.heroes

/**
 * Adventure-map hero sprites, read from the player's own game files like every other map sprite.
 *
 * Each hero class DEF (and each player-colour flag DEF drawn on top of it) has ten groups: 0-4 standing and 5-9
 * walking, facing north, north-east, east, south-east and south. The three westward directions reuse the eastward
 * groups mirrored.
 */
object HeroDefs {
    private const val HERO_CLASS_COUNT = 18
    private const val FLAG_COLOUR_COUNT = 8
    const val MOVING_GROUP_OFFSET = 5
    const val GROUP_COUNT = 10

    val heroDefNames: List<String> = (0 until HERO_CLASS_COUNT).map { "ah%02d_.def".format(it) }
    val flagDefNames: List<String> = (0 until FLAG_COLOUR_COUNT).map { "af%02d.def".format(it) }
    val allDefNames: Set<String> = (heroDefNames + flagDefNames).toSet()
}

/** Map directions; y grows southwards. [group] is the standing group drawn for it, mirrored when [isMirrored]. */
enum class Direction(val dx: Int, val dy: Int, val group: Int, val isMirrored: Boolean) {
    N(0, -1, 0, false),
    NE(1, -1, 1, false),
    E(1, 0, 2, false),
    SE(1, 1, 3, false),
    S(0, 1, 4, false),
    SW(-1, 1, 3, true),
    W(-1, 0, 2, true),
    NW(-1, -1, 1, true);

    companion object {
        /** Direction of a step from one tile towards another; [S] when they are the same tile. */
        fun between(from: Tile, to: Tile): Direction {
            val dx = (to.x - from.x).coerceIn(-1, 1)
            val dy = (to.y - from.y).coerceIn(-1, 1)
            return entries.firstOrNull { it.dx == dx && it.dy == dy } ?: S
        }
    }
}
