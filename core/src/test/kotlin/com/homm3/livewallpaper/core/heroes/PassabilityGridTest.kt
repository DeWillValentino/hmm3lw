package com.homm3.livewallpaper.core.heroes

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassabilityGridTest {
    private val everywhere = TileRect(0, 0, 99, 99)

    @Test
    fun `open ground gives a straight path ending at the goal`() {
        val grid = PassabilityGrid(10, 10)
        val path = assertNotNull(grid.findPath(Tile(0, 0), Tile(4, 0), everywhere))
        assertEquals(listOf(Tile(1, 0), Tile(2, 0), Tile(3, 0), Tile(4, 0)), path)
    }

    @Test
    fun `diagonal moves are used`() {
        val grid = PassabilityGrid(10, 10)
        val path = assertNotNull(grid.findPath(Tile(0, 0), Tile(3, 3), everywhere))
        assertEquals(3, path.size)
    }

    @Test
    fun `walls are walked around`() {
        val grid = PassabilityGrid(7, 7)
        for (y in 0..4) grid.setBlocked(3, y, true)
        val path = assertNotNull(grid.findPath(Tile(1, 1), Tile(5, 1), everywhere))
        assertTrue(path.none { grid.isBlocked(it) })
        assertTrue(path.any { it.y >= 5 }, "path should go below the wall: $path")
    }

    @Test
    fun `no path through a closed wall`() {
        val grid = PassabilityGrid(7, 7)
        for (y in 0..6) grid.setBlocked(3, y, true)
        assertNull(grid.findPath(Tile(1, 1), Tile(5, 1), everywhere))
    }

    @Test
    fun `no squeezing between two blocked corners`() {
        val grid = PassabilityGrid(3, 3)
        grid.setBlocked(1, 0, true)
        grid.setBlocked(0, 1, true)
        grid.setBlocked(2, 1, true)
        grid.setBlocked(1, 2, true)
        grid.setBlocked(2, 2, true)
        assertNull(grid.findPath(Tile(0, 0), Tile(1, 1), everywhere))
    }

    @Test
    fun `a blocked goal can be entered`() {
        val grid = PassabilityGrid(5, 5)
        grid.setBlocked(2, 2, true)
        val path = assertNotNull(grid.findPath(Tile(0, 2), Tile(2, 2), everywhere))
        assertEquals(Tile(2, 2), path.last())
    }

    @Test
    fun `entrances facing south are not entered from above`() {
        val grid = PassabilityGrid(5, 5)
        grid.setBlocked(2, 2, true)
        val path = assertNotNull(grid.findPath(Tile(2, 0), Tile(2, 2), everywhere, enterGoalFromBelowOnly = true))
        val beforeGoal = path[path.size - 2]
        assertTrue(beforeGoal.y >= 2, "entered from $beforeGoal")
    }

    @Test
    fun `paths stay inside the bounds`() {
        val grid = PassabilityGrid(10, 10)
        for (y in 0..4) grid.setBlocked(5, y, true)
        assertNull(grid.findPath(Tile(2, 2), Tile(8, 2), TileRect(0, 0, 9, 4)))
        assertNotNull(grid.findPath(Tile(2, 2), Tile(8, 2), TileRect(0, 0, 9, 9)))
    }

    @Test
    fun `starting on a blocked tile is allowed`() {
        val grid = PassabilityGrid(5, 5)
        grid.setBlocked(2, 2, true)
        assertNotNull(grid.findPath(Tile(2, 2), Tile(4, 4), everywhere))
    }
}
