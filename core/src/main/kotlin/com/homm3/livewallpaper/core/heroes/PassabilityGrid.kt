package com.homm3.livewallpaper.core.heroes

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Tile(val x: Int, val y: Int)

/** Inclusive tile rectangle. */
data class TileRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0 + 1
    val height: Int get() = y1 - y0 + 1
    operator fun contains(tile: Tile) = tile.x in x0..x1 && tile.y in y0..y1
}

/** Which tiles of one map level a hero can walk over. */
class PassabilityGrid(val width: Int, val height: Int) {
    private val blocked = BooleanArray(width * height)

    fun isInside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun isBlocked(x: Int, y: Int) = !isInside(x, y) || blocked[y * width + x]

    fun isBlocked(tile: Tile) = isBlocked(tile.x, tile.y)

    fun setBlocked(x: Int, y: Int, value: Boolean) {
        if (isInside(x, y)) blocked[y * width + x] = value
    }

    fun copy(): PassabilityGrid = PassabilityGrid(width, height).also { blocked.copyInto(it.blocked) }

    /**
     * Shortest 8-way path from [start] to [goal] that stays inside [bounds], as the tiles after [start] (the last
     * one is [goal]); `null` when there is none. [start] may be blocked (a hero standing on an entrance) and [goal]
     * is entered even when blocked, as an object's visit tile is. A diagonal step needs one of the two tiles beside
     * it open, so heroes don't squeeze between two blocked corners. With [enterGoalFromBelowOnly] the last step may
     * not come from the row above the goal, the way building entrances face south.
     */
    fun findPath(start: Tile, goal: Tile, bounds: TileRect, enterGoalFromBelowOnly: Boolean = false): List<Tile>? {
        if (start == goal) return emptyList()
        val area = clip(bounds)
        if (start !in area || goal !in area) return null

        val w = area.width
        val size = w * area.height
        fun index(x: Int, y: Int) = (y - area.y0) * w + (x - area.x0)

        val cost = IntArray(size) { Int.MAX_VALUE }
        val cameFrom = IntArray(size) { -1 }
        val closed = BooleanArray(size)
        val open = PriorityQueue<Long>()
        val startIndex = index(start.x, start.y)
        val goalIndex = index(goal.x, goal.y)
        cost[startIndex] = 0
        open.add(packEntry(heuristic(start.x, start.y, goal), startIndex))

        while (open.isNotEmpty()) {
            val current = (open.poll() and INDEX_MASK).toInt()
            if (closed[current]) continue
            if (current == goalIndex) return rebuild(cameFrom, goalIndex, startIndex, area)
            closed[current] = true
            val cx = current % w + area.x0
            val cy = current / w + area.y0

            for ((dx, dy) in NEIGHBOURS) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < area.x0 || nx > area.x1 || ny < area.y0 || ny > area.y1) continue
                val isGoal = nx == goal.x && ny == goal.y
                if (isGoal) {
                    if (enterGoalFromBelowOnly && dy > 0) continue
                } else if (isBlocked(nx, ny)) {
                    continue
                }
                val isDiagonal = dx != 0 && dy != 0
                if (isDiagonal && isBlocked(cx + dx, cy) && isBlocked(cx, cy + dy)) continue

                val next = index(nx, ny)
                if (closed[next]) continue
                val nextCost = cost[current] + if (isDiagonal) DIAGONAL_COST else STRAIGHT_COST
                if (nextCost >= cost[next]) continue
                cost[next] = nextCost
                cameFrom[next] = current
                open.add(packEntry(nextCost + heuristic(nx, ny, goal), next))
            }
        }
        return null
    }

    private fun clip(bounds: TileRect) = TileRect(
        max(0, bounds.x0), max(0, bounds.y0),
        min(width - 1, bounds.x1), min(height - 1, bounds.y1),
    )

    private fun rebuild(cameFrom: IntArray, goalIndex: Int, startIndex: Int, area: TileRect): List<Tile> {
        val w = area.width
        val path = ArrayDeque<Tile>()
        var node = goalIndex
        while (node != startIndex) {
            path.addFirst(Tile(node % w + area.x0, node / w + area.y0))
            node = cameFrom[node]
        }
        return path.toList()
    }

    private companion object {
        const val STRAIGHT_COST = 10
        const val DIAGONAL_COST = 14
        const val INDEX_BITS = 32
        const val INDEX_MASK = 0xFFFFFFFFL
        val NEIGHBOURS = listOf(
            0 to -1, 1 to 0, 0 to 1, -1 to 0,
            1 to -1, 1 to 1, -1 to 1, -1 to -1,
        )

        fun packEntry(priority: Int, index: Int): Long = (priority.toLong() shl INDEX_BITS) or index.toLong()

        /** Octile distance: the exact cost of the best path on an open grid. */
        fun heuristic(x: Int, y: Int, goal: Tile): Int {
            val dx = abs(x - goal.x)
            val dy = abs(y - goal.y)
            return STRAIGHT_COST * (dx + dy) + (DIAGONAL_COST - 2 * STRAIGHT_COST) * min(dx, dy)
        }
    }
}
