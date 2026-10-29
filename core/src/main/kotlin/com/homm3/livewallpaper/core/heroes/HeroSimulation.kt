package com.homm3.livewallpaper.core.heroes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Something on the map a hero can head for. [id] is the object's index in its level's object list. Heroes stop next
 * to a [isRemovable] object (a resource, an artifact, a monster) and take it off the map, freeing [blockedTiles];
 * other objects (mines, towns, shrines) they walk into through their [visitTile] and leave again.
 */
data class HeroTarget(
    val id: Int,
    val visitTile: Tile,
    val isRemovable: Boolean,
    val blockedTiles: List<Tile>,
)

class WanderingHero(val bodyLook: Int, val flagLook: Int, start: Tile) {
    enum class State { IDLE, WALKING }

    var tile: Tile = start
        internal set
    var facing: Direction = Direction.S
        internal set
    var state: State = State.IDLE
        internal set

    /** Seconds spent walking the current path; drives the walking animation. */
    var walkTime = 0f
        internal set

    internal var path: List<Tile> = emptyList()
    internal var pathIndex = 0
    internal var progress = 0f
    internal var idleTimer = 0f
    internal var target: HeroTarget? = null
    internal val recentTargets = ArrayDeque<Int>()

    val isWalking: Boolean get() = state == State.WALKING

    /** Tile coordinates the hero is drawn at, between two tiles while walking. */
    val x: Float get() = if (isWalking) lerp(tile.x, path[pathIndex].x) else tile.x.toFloat()
    val y: Float get() = if (isWalking) lerp(tile.y, path[pathIndex].y) else tile.y.toFloat()

    private fun lerp(from: Int, to: Int) = from + (to - from) * progress
}

/**
 * Heroes wandering one level of a map: they pick objects near them, walk there and visit or collect them, then move
 * on, never leaving the window they were spawned in. Pure logic, no rendering, so it can be unit-tested.
 */
class HeroSimulation(
    private val baseGrid: PassabilityGrid,
    private val targets: List<HeroTarget>,
    private val random: Random = Random.Default,
    private val onCollected: (HeroTarget) -> Unit = {},
) {
    private var grid = baseGrid.copy()
    private val collected = mutableSetOf<Int>()
    private val claimed = mutableSetOf<Int>()
    private var window = TileRect(0, 0, -1, -1)
    private val _heroes = mutableListOf<WanderingHero>()

    val heroes: List<WanderingHero> get() = _heroes
    val isAnyWalking: Boolean get() = _heroes.any { it.isWalking }

    /**
     * Restores collected objects and spawns [heroCount] heroes inside [spawnArea]; they roam [window], which should
     * cover [spawnArea] plus a margin. Each hero gets a random body below [bodyLooks] and flag below [flagLooks].
     */
    fun reset(window: TileRect, spawnArea: TileRect, heroCount: Int, bodyLooks: Int, flagLooks: Int) {
        grid = baseGrid.copy()
        collected.clear()
        claimed.clear()
        _heroes.clear()
        this.window = window
        if (bodyLooks <= 0 || flagLooks <= 0) return

        val flags = (0 until flagLooks).shuffled(random)
        repeat(heroCount) { index ->
            val start = findSpawnTile(spawnArea) ?: return
            val hero = WanderingHero(random.nextInt(bodyLooks), flags[index % flags.size], start)
            hero.facing = Direction.entries.random(random)
            hero.idleTimer = random.nextFloat() * FIRST_MOVE_MAX_DELAY
            _heroes += hero
        }
    }

    fun clear() {
        _heroes.clear()
        claimed.clear()
    }

    fun update(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        _heroes.forEach { hero ->
            when (hero.state) {
                WanderingHero.State.IDLE -> {
                    hero.idleTimer -= dt
                    if (hero.idleTimer <= 0f) planNextMove(hero)
                }
                WanderingHero.State.WALKING -> walk(hero, dt)
            }
        }
    }

    private fun walk(hero: WanderingHero, dt: Float) {
        hero.walkTime += dt
        var remaining = dt
        while (remaining > 0f && hero.isWalking) {
            val next = hero.path[hero.pathIndex]
            val isDiagonal = next.x != hero.tile.x && next.y != hero.tile.y
            val stepSeconds = if (isDiagonal) SECONDS_PER_TILE * DIAGONAL_FACTOR else SECONDS_PER_TILE
            val needed = (1f - hero.progress) * stepSeconds
            if (remaining < needed) {
                hero.progress += remaining / stepSeconds
                return
            }
            remaining -= needed
            hero.tile = next
            hero.progress = 0f
            hero.pathIndex++
            if (hero.pathIndex >= hero.path.size) {
                arrive(hero)
            } else {
                hero.facing = Direction.between(hero.tile, hero.path[hero.pathIndex])
            }
        }
    }

    private fun arrive(hero: WanderingHero) {
        hero.state = WanderingHero.State.IDLE
        hero.path = emptyList()
        hero.pathIndex = 0
        hero.progress = 0f
        val target = hero.target
        hero.target = null
        if (target == null) {
            hero.idleTimer = randomBetween(WANDER_PAUSE_MIN, WANDER_PAUSE_MAX)
            return
        }
        claimed -= target.id
        remember(hero, target.id)
        if (target.isRemovable) {
            if (hero.tile != target.visitTile) hero.facing = Direction.between(hero.tile, target.visitTile)
            collect(target)
        }
        hero.idleTimer = randomBetween(VISIT_PAUSE_MIN, VISIT_PAUSE_MAX)
    }

    private fun collect(target: HeroTarget) {
        if (!collected.add(target.id)) return
        target.blockedTiles.forEach { grid.setBlocked(it.x, it.y, false) }
        grid.setBlocked(target.visitTile.x, target.visitTile.y, false)
        onCollected(target)
    }

    private fun remember(hero: WanderingHero, targetId: Int) {
        hero.recentTargets.addLast(targetId)
        while (hero.recentTargets.size > RECENT_TARGETS) hero.recentTargets.removeFirst()
    }

    private fun planNextMove(hero: WanderingHero) {
        if (tryHeadForTarget(hero) || tryWander(hero)) return
        hero.idleTimer = randomBetween(WANDER_PAUSE_MIN, WANDER_PAUSE_MAX)
    }

    private fun tryHeadForTarget(hero: WanderingHero): Boolean {
        val candidates = targets
            .asSequence()
            .filter { it.id !in collected && it.id !in claimed && it.id !in hero.recentTargets }
            .filter { it.visitTile in window && it.visitTile != hero.tile }
            .filter { !occupiedByOtherHero(hero, it.visitTile) }
            .sortedBy { distance(hero.tile, it.visitTile) }
            .take(NEAREST_TARGETS)
            .toList()
            .shuffled(random)

        for (target in candidates) {
            val fullPath = grid.findPath(
                start = hero.tile,
                goal = target.visitTile,
                bounds = window,
                enterGoalFromBelowOnly = !target.isRemovable,
            ) ?: continue
            val walkPath = if (target.isRemovable) fullPath.dropLast(1) else fullPath
            claimed += target.id
            hero.target = target
            if (walkPath.isEmpty()) {
                arrive(hero)
            } else {
                startWalking(hero, walkPath)
            }
            return true
        }
        return false
    }

    private fun tryWander(hero: WanderingHero): Boolean {
        repeat(WANDER_ATTEMPTS) {
            val goal = randomOpenTile(window) ?: return false
            if (goal == hero.tile || distance(hero.tile, goal) > MAX_WANDER_DISTANCE) return@repeat
            val path = grid.findPath(hero.tile, goal, window) ?: return@repeat
            hero.target = null
            startWalking(hero, path)
            return true
        }
        return false
    }

    private fun startWalking(hero: WanderingHero, path: List<Tile>) {
        hero.path = path
        hero.pathIndex = 0
        hero.progress = 0f
        hero.walkTime = 0f
        hero.state = WanderingHero.State.WALKING
        hero.facing = Direction.between(hero.tile, path.first())
    }

    private fun findSpawnTile(area: TileRect): Tile? {
        repeat(SPAWN_ATTEMPTS) {
            val tile = randomOpenTile(area) ?: return null
            if (_heroes.all { distance(it.tile, tile) >= MIN_SPAWN_SPACING }) return tile
        }
        return null
    }

    private fun randomOpenTile(area: TileRect): Tile? {
        val x0 = max(0, area.x0)
        val y0 = max(0, area.y0)
        val x1 = min(grid.width - 1, area.x1)
        val y1 = min(grid.height - 1, area.y1)
        if (x0 > x1 || y0 > y1) return null
        repeat(OPEN_TILE_ATTEMPTS) {
            val tile = Tile(random.nextInt(x0, x1 + 1), random.nextInt(y0, y1 + 1))
            if (!grid.isBlocked(tile) && !isTargetTile(tile)) return tile
        }
        return null
    }

    private fun isTargetTile(tile: Tile) = targets.any { it.id !in collected && it.visitTile == tile }

    private fun occupiedByOtherHero(hero: WanderingHero, tile: Tile) =
        _heroes.any { it !== hero && it.tile == tile }

    private fun randomBetween(min: Float, max: Float) = min + random.nextFloat() * (max - min)

    companion object {
        /** Walking speed: a straight step takes this long; a diagonal one [DIAGONAL_FACTOR] times longer. */
        const val SECONDS_PER_TILE = 0.5f
        const val DIAGONAL_FACTOR = 1.41f

        /** Longest time step [update] simulates, so heroes don't jump after the wallpaper was hidden. */
        const val MAX_STEP_SECONDS = 0.25f

        private const val FIRST_MOVE_MAX_DELAY = 1.5f
        private const val VISIT_PAUSE_MIN = 1.2f
        private const val VISIT_PAUSE_MAX = 3.0f
        private const val WANDER_PAUSE_MIN = 1.5f
        private const val WANDER_PAUSE_MAX = 3.5f
        private const val NEAREST_TARGETS = 4
        private const val RECENT_TARGETS = 6
        private const val WANDER_ATTEMPTS = 8
        private const val MAX_WANDER_DISTANCE = 10
        private const val SPAWN_ATTEMPTS = 40
        private const val OPEN_TILE_ATTEMPTS = 60
        private const val MIN_SPAWN_SPACING = 3

        fun distance(a: Tile, b: Tile) = max(abs(a.x - b.x), abs(a.y - b.y))
    }
}
