package com.homm3.livewallpaper.core.heroes

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroSimulationTest {
    private val area = TileRect(0, 0, 19, 19)

    private fun run(simulation: HeroSimulation, seconds: Float) {
        var left = seconds
        while (left > 0f) {
            simulation.update(0.1f)
            left -= 0.1f
        }
    }

    @Test
    fun `heroes spawn on open tiles inside the spawn area`() {
        val grid = PassabilityGrid(20, 20)
        for (x in 0..19) grid.setBlocked(x, 0, true)
        val simulation = HeroSimulation(grid, emptyList(), Random(1))
        simulation.reset(area, TileRect(2, 0, 10, 10), heroCount = 2, bodyLooks = 3, flagLooks = 8)
        assertEquals(2, simulation.heroes.size)
        simulation.heroes.forEach { hero ->
            assertFalse(grid.isBlocked(hero.tile))
            assertTrue(hero.tile in TileRect(2, 0, 10, 10))
            assertTrue(hero.bodyLook in 0 until 3)
        }
        assertTrue(simulation.heroes.map { it.flagLook }.distinct().size == 2, "each hero gets its own colour")
    }

    @Test
    fun `no heroes without sprites`() {
        val simulation = HeroSimulation(PassabilityGrid(20, 20), emptyList(), Random(1))
        simulation.reset(area, area, heroCount = 2, bodyLooks = 0, flagLooks = 8)
        assertTrue(simulation.heroes.isEmpty())
    }

    @Test
    fun `a hero walks to a resource, collects it and frees its tile`() {
        val grid = PassabilityGrid(20, 20)
        val resource = HeroTarget(id = 7, visitTile = Tile(12, 10), isRemovable = true, blockedTiles = listOf(Tile(12, 10)))
        grid.setBlocked(12, 10, true)
        val collected = mutableListOf<Int>()
        val heroTilesWhenCollecting = mutableListOf<Tile>()
        lateinit var simulation: HeroSimulation
        simulation = HeroSimulation(grid, listOf(resource), Random(3)) {
            collected += it.id
            heroTilesWhenCollecting += simulation.heroes.single().tile
        }
        simulation.reset(area, TileRect(5, 10, 5, 10), heroCount = 1, bodyLooks = 1, flagLooks = 1)
        assertEquals(Tile(5, 10), simulation.heroes.single().tile)

        run(simulation, seconds = 10f)

        assertEquals(listOf(7), collected)
        assertEquals(1, HeroSimulation.distance(heroTilesWhenCollecting.single(), resource.visitTile), "stops next to it")
    }

    @Test
    fun `a hero walks into a building entrance and leaves it standing`() {
        val grid = PassabilityGrid(20, 20)
        val mine = HeroTarget(id = 2, visitTile = Tile(10, 10), isRemovable = false, blockedTiles = emptyList())
        grid.setBlocked(10, 10, true)
        grid.setBlocked(11, 10, true)
        val collected = mutableListOf<Int>()
        val simulation = HeroSimulation(grid, listOf(mine), Random(5)) { collected += it.id }
        simulation.reset(area, TileRect(10, 14, 10, 14), heroCount = 1, bodyLooks = 1, flagLooks = 1)

        var reached = false
        repeat(100) {
            simulation.update(0.1f)
            if (simulation.heroes.single().tile == mine.visitTile) reached = true
        }

        assertTrue(reached, "hero should stand in the entrance at some point")
        assertTrue(collected.isEmpty(), "buildings stay on the map")
    }

    @Test
    fun `heroes keep moving inside their window`() {
        val grid = PassabilityGrid(40, 40)
        val window = TileRect(10, 10, 20, 20)
        val simulation = HeroSimulation(grid, emptyList(), Random(9))
        simulation.reset(window, TileRect(12, 12, 18, 18), heroCount = 2, bodyLooks = 1, flagLooks = 2)
        val starts = simulation.heroes.map { it.tile }

        var walked = false
        repeat(300) {
            simulation.update(0.1f)
            simulation.heroes.forEach { hero ->
                assertTrue(hero.tile in window, "${hero.tile} left the window")
                if (hero.isWalking) walked = true
            }
        }
        assertTrue(walked)
        assertTrue(simulation.heroes.map { it.tile } != starts)
    }

    @Test
    fun `reset brings collected objects back`() {
        val grid = PassabilityGrid(20, 20)
        val chest = HeroTarget(id = 1, visitTile = Tile(8, 5), isRemovable = true, blockedTiles = listOf(Tile(8, 5)))
        grid.setBlocked(8, 5, true)
        val collected = mutableListOf<Int>()
        val simulation = HeroSimulation(grid, listOf(chest), Random(2)) { collected += it.id }
        simulation.reset(area, TileRect(5, 5, 5, 5), heroCount = 1, bodyLooks = 1, flagLooks = 1)
        run(simulation, 8f)
        simulation.reset(area, TileRect(5, 5, 5, 5), heroCount = 1, bodyLooks = 1, flagLooks = 1)
        run(simulation, 8f)
        assertEquals(listOf(1, 1), collected)
    }

    @Test
    fun `big time steps are capped`() {
        val grid = PassabilityGrid(20, 20)
        val simulation = HeroSimulation(grid, emptyList(), Random(4))
        simulation.reset(area, TileRect(5, 5, 5, 5), heroCount = 1, bodyLooks = 1, flagLooks = 1)
        run(simulation, 2f) // start walking
        val before = simulation.heroes.single().let { it.x to it.y }
        simulation.update(60f)
        val after = simulation.heroes.single().let { it.x to it.y }
        val moved = maxOf(kotlin.math.abs(after.first - before.first), kotlin.math.abs(after.second - before.second))
        assertTrue(moved <= 1f, "moved $moved tiles in one capped step")
    }

    @Test
    fun `directions mirror the eastward sprites for the west`() {
        assertEquals(Direction.W, Direction.between(Tile(5, 5), Tile(4, 5)))
        assertTrue(Direction.W.isMirrored)
        assertEquals(Direction.E.group, Direction.W.group)
        assertEquals(Direction.NE, Direction.between(Tile(5, 5), Tile(9, 1)))
        assertFalse(Direction.S.isMirrored)
    }
}
