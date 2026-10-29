package com.homm3.livewallpaper.core.map.layers

import com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion
import com.badlogic.gdx.maps.MapLayer
import com.badlogic.gdx.maps.tiled.renderers.BatchTiledMapRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Array as GdxArray
import com.homm3.livewallpaper.core.GameConfig.TILE_SIZE
import com.homm3.livewallpaper.core.assets.SpriteSet
import com.homm3.livewallpaper.core.assets.indexed.PaletteColor
import com.homm3.livewallpaper.core.assets.indexed.PaletteRegion
import com.homm3.livewallpaper.core.defs.TerrainDef
import com.homm3.livewallpaper.core.heroes.Footprint
import com.homm3.livewallpaper.core.heroes.HeroDefs
import com.homm3.livewallpaper.core.heroes.HeroSimulation
import com.homm3.livewallpaper.core.heroes.HeroTarget
import com.homm3.livewallpaper.core.heroes.PassabilityGrid
import com.homm3.livewallpaper.core.heroes.Tile
import com.homm3.livewallpaper.core.heroes.TileRect
import com.homm3.livewallpaper.core.heroes.WanderingHero
import com.homm3.livewallpaper.core.render.AnimationClock
import com.homm3.livewallpaper.parser.h3m.H3mMap
import com.homm3.livewallpaper.parser.h3m.H3mObject
import com.homm3.livewallpaper.parser.h3m.H3mObjectType
import ktx.log.logger
import kotlin.math.floor

/**
 * Heroes that roam the part of the map on screen: they walk to nearby objects, collect resources, artifacts and
 * monsters (taking them off [objectsLayer]) and visit buildings. They are respawned for every new view.
 * Sprites come from the player's game files. Use on the rendering thread only.
 */
class HeroesLayer(
    private val spriteSet: SpriteSet,
    private val h3m: H3mMap,
    private val isUnderground: Boolean,
    private val objectsLayer: ObjectsLayer,
) : MapLayer() {
    private class Look(val groups: List<GdxArray<AtlasRegion>>)

    private val mapSize = h3m.header.size
    private val bodies: List<Look> by lazy(LazyThreadSafetyMode.NONE) { loadLooks(HeroDefs.heroDefNames) }
    private val flags: List<Look> by lazy(LazyThreadSafetyMode.NONE) { loadLooks(HeroDefs.flagDefNames) }
    private val simulationLazy = lazy(LazyThreadSafetyMode.NONE) {
        HeroSimulation(buildGrid(), buildTargets()) { target -> objectsLayer.setHidden(target.id, true) }
    }
    private val simulation: HeroSimulation by simulationLazy

    val isAnyWalking: Boolean get() = simulationLazy.isInitialized() && simulation.isAnyWalking

    init {
        name = "heroes"
    }

    /** Brings collected objects back and spawns new heroes around the view [viewBounds] (world units). */
    fun respawn(viewBounds: Rectangle) {
        objectsLayer.showAll()
        val view = toTiles(viewBounds)
        val window = TileRect(view.x0 - WINDOW_MARGIN, view.y0 - WINDOW_MARGIN, view.x1 + WINDOW_MARGIN, view.y1 + WINDOW_MARGIN)
        val spawnArea = TileRect(view.x0 + SPAWN_INSET, view.y0 + SPAWN_INSET, view.x1 - SPAWN_INSET, view.y1 - SPAWN_INSET)
        val heroCount = heroCountFor(view)
        simulation.reset(window, spawnArea, heroCount, bodies.size, flags.size)
        log.debug { "Spawned ${simulation.heroes.size}/$heroCount heroes in $view (underground=$isUnderground)" }
    }

    /** Removes the heroes and brings collected objects back. */
    fun clear() {
        if (simulationLazy.isInitialized()) simulation.clear()
        objectsLayer.showAll()
    }

    fun update(deltaSeconds: Float) {
        if (simulationLazy.isInitialized()) simulation.update(deltaSeconds)
    }

    fun render(renderer: BatchTiledMapRenderer) {
        if (!simulationLazy.isInitialized() || simulation.heroes.isEmpty()) return
        simulation.heroes.sortedBy { it.y }.forEach { hero -> draw(renderer, hero) }
    }

    private fun draw(renderer: BatchTiledMapRenderer, hero: WanderingHero) {
        val body = bodies.getOrNull(hero.bodyLook) ?: return
        val flag = flags.getOrNull(hero.flagLook)
        val group = hero.facing.group + if (hero.isWalking) HeroDefs.MOVING_GROUP_OFFSET else 0
        val frames = body.groups[group]
        val frameIndex = if (hero.isWalking) {
            floor(hero.walkTime * WALK_FRAMES_PER_SECOND).toInt()
        } else {
            AnimationClock.frameStep.toInt()
        }
        // Heroes are drawn like a 3x2 object whose bottom-right tile is one tile east of where they stand.
        val anchorX = (hero.x + 2) * TILE_SIZE
        val anchorY = (hero.y + 1) * TILE_SIZE
        drawFrame(renderer, frames, frameIndex, anchorX, anchorY, hero.facing.isMirrored)
        if (flag != null) drawFrame(renderer, flag.groups[group], frameIndex, anchorX, anchorY, hero.facing.isMirrored)
    }

    private fun drawFrame(
        renderer: BatchTiledMapRenderer,
        frames: GdxArray<AtlasRegion>,
        frameIndex: Int,
        anchorX: Float,
        anchorY: Float,
        isMirrored: Boolean,
    ) {
        if (frames.isEmpty) return
        val frame = frames[Math.floorMod(frameIndex, frames.size)]
        val paletteSlot = (frame as? PaletteRegion)?.paletteSlots?.at(AnimationClock.frameStep) ?: 0
        renderer.batch.packedColor = PaletteColor.encode(paletteSlot)
        // Same placement as MapSprite; a mirrored frame keeps the canvas and flips the frame's place inside it.
        val y = anchorY - frame.offsetY - frame.packedHeight
        if (isMirrored) {
            val left = anchorX - frame.offsetX - frame.packedWidth
            renderer.batch.draw(frame, left + frame.packedWidth, y, -frame.packedWidth.toFloat(), frame.packedHeight.toFloat())
        } else {
            renderer.batch.draw(frame, anchorX + frame.offsetX - frame.originalWidth, y)
        }
    }

    /** DEFs from [defNames] whose ten facing groups all loaded. */
    private fun loadLooks(defNames: List<String>): List<Look> = defNames.mapNotNull { defName ->
        val groups = (0 until HeroDefs.GROUP_COUNT).map { spriteSet.getGroupFrames(defName, it) }
        if (groups.any { it.isEmpty }) null else Look(groups)
    }.also { looks ->
        if (looks.isEmpty()) log.info { "No hero sprites among $defNames; wandering heroes are off" }
    }

    private fun buildGrid(): PassabilityGrid {
        val grid = PassabilityGrid(mapSize, mapSize)
        val tileOffset = if (isUnderground) mapSize * mapSize else 0
        for (y in 0 until mapSize) {
            for (x in 0 until mapSize) {
                val terrain = h3m.tiles[tileOffset + mapSize * y + x].terrain
                if (terrain in IMPASSABLE_TERRAINS) grid.setBlocked(x, y, true)
            }
        }
        objectsLayer.levelObjects.forEach { obj ->
            if (obj.objectType in IGNORED_TYPES) return@forEach
            blockedTiles(obj).forEach { grid.setBlocked(it.x, it.y, true) }
        }
        return grid
    }

    private fun buildTargets(): List<HeroTarget> = objectsLayer.levelObjects.mapIndexedNotNull { id, obj ->
        if (obj.objectType in IGNORED_TYPES || obj.objectType in NOT_VISITED_TYPES) return@mapIndexedNotNull null
        val visit = Footprint.visitOffsets(obj.def.activeCells).firstOrNull() ?: return@mapIndexedNotNull null
        val visitTile = Tile(obj.x + visit.dx, obj.y + visit.dy)
        if (visitTile.x !in 0 until mapSize || visitTile.y !in 0 until mapSize) return@mapIndexedNotNull null
        val isRemovable = obj.objectType in REMOVABLE_TYPES
        HeroTarget(id, visitTile, isRemovable, if (isRemovable) blockedTiles(obj) else emptyList())
    }

    private fun blockedTiles(obj: H3mObject): List<Tile> =
        Footprint.blockedOffsets(obj.def.passableCells).map { Tile(obj.x + it.dx, obj.y + it.dy) }

    private fun toTiles(bounds: Rectangle) = TileRect(
        floor(bounds.x / TILE_SIZE).toInt(),
        floor(bounds.y / TILE_SIZE).toInt(),
        floor((bounds.x + bounds.width) / TILE_SIZE).toInt() - 1,
        floor((bounds.y + bounds.height) / TILE_SIZE).toInt() - 1,
    )

    private fun heroCountFor(view: TileRect): Int {
        val tiles = view.width * view.height
        return when {
            tiles < SMALL_VIEW_TILES -> 1
            tiles < LARGE_VIEW_TILES -> 2
            else -> 3
        }
    }

    private companion object {
        val log = logger<HeroesLayer>()

        /** Heroes may walk this many tiles beyond the view (the launcher scrolls and zooms it a little). */
        const val WINDOW_MARGIN = 3
        const val SPAWN_INSET = 1
        const val SMALL_VIEW_TILES = 150
        const val LARGE_VIEW_TILES = 450

        /** Eight walking frames per tile, matching HeroSimulation.SECONDS_PER_TILE. */
        const val WALK_FRAMES_PER_SECOND = 8f / HeroSimulation.SECONDS_PER_TILE

        val IMPASSABLE_TERRAINS = setOf(TerrainDef.watrtl.id, TerrainDef.rocktl.id)

        /** Not drawn by the wallpaper, so they neither block heroes nor attract them. */
        val IGNORED_TYPES = setOf(
            H3mObjectType.HERO,
            H3mObjectType.RANDOM_HERO,
            H3mObjectType.HERO_PLACEHOLDER,
            H3mObjectType.EVENT,
            H3mObjectType.GRAIL,
        )

        /** Visitable in the game but not worth a walk here: water objects and teleports. */
        val NOT_VISITED_TYPES = setOf(
            H3mObjectType.BOAT,
            H3mObjectType.BUOY,
            H3mObjectType.WHIRLPOOL,
            H3mObjectType.FLOTSAM,
            H3mObjectType.OCEAN_BOTTLE,
            H3mObjectType.SEA_CHEST,
            H3mObjectType.SHIPWRECK_SURVIVOR,
            H3mObjectType.SIRENS,
            H3mObjectType.MERMAID,
        )

        /** Picked up or defeated: they leave the map until the next view. */
        val REMOVABLE_TYPES = ObjectsLayer.MONSTER_TYPES + setOf(
            H3mObjectType.RESOURCE,
            H3mObjectType.RANDOM_RESOURCE,
            H3mObjectType.ARTIFACT,
            H3mObjectType.RANDOM_ART,
            H3mObjectType.RANDOM_TREASURE_ART,
            H3mObjectType.RANDOM_MINOR_ART,
            H3mObjectType.RANDOM_MAJOR_ART,
            H3mObjectType.RANDOM_RELIC_ART,
            H3mObjectType.TREASURE_CHEST,
            H3mObjectType.CAMPFIRE,
            H3mObjectType.PANDORAS_BOX,
            H3mObjectType.SPELL_SCROLL,
            H3mObjectType.SCHOLAR,
            H3mObjectType.PRISON,
        )
    }
}
