package com.homm3.livewallpaper.core.map.layers

import com.badlogic.gdx.maps.MapLayer
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.maps.tiled.renderers.BatchTiledMapRenderer
import com.badlogic.gdx.math.Rectangle
import com.homm3.livewallpaper.core.GameConfig.FRUSTUM_PADDING_TILES
import com.homm3.livewallpaper.core.GameConfig.TILE_SIZE
import com.homm3.livewallpaper.core.assets.SpriteSet
import com.homm3.livewallpaper.core.defs.ObjectsRandomizer
import com.homm3.livewallpaper.core.map.MapSprite
import com.homm3.livewallpaper.core.map.MonsterAnchor
import com.homm3.livewallpaper.parser.h3m.H3mMap
import com.homm3.livewallpaper.parser.h3m.H3mObject
import com.homm3.livewallpaper.parser.h3m.H3mObjectType

class ObjectsLayer(assets: SpriteSet, h3m: H3mMap, isUnderground: Boolean) : MapLayer() {
    private val randomizer = ObjectsRandomizer(hasHotaAssets = assets.hasHotaAssets)

    /** This level's objects in drawing order; an object's index here is its id for [setHidden]. */
    val levelObjects: List<H3mObject> = h3m
        .objects
        .sorted()
        .filter { it.z == if (isUnderground) 1 else 0 }

    private val sprites = levelObjects
        .map { obj ->
            val frames = assets.getObjectFrames(randomizer.resolveSpriteName(obj))
            MapSprite(obj, frames, anchorShiftTiles(obj, frames))
        }

    private val spritesIndices: Map<Int, Map<Int, List<Int>>> = buildSpatialIndex(levelObjects)

    /** Objects taken off the map by wandering heroes; they come back with [showAll]. */
    private val hidden = BooleanArray(sprites.size)

    private var lastStartX = Int.MIN_VALUE
    private var lastStartY = Int.MIN_VALUE
    private var lastEndX = Int.MIN_VALUE
    private var lastEndY = Int.MIN_VALUE
    private val visibleSprites = mutableListOf<MapSprite>()
    private val visibleIds = mutableListOf<Int>()

    init {
        name = "objects"
    }

    private fun anchorShiftTiles(obj: H3mObject, frames: GdxArray<TextureAtlas.AtlasRegion>): Int {
        val isMonster = obj.objectType in MONSTER_TYPES
        if (!isMonster || frames.isEmpty) return 0
        return MonsterAnchor.shiftTiles(frames.first().originalWidth, obj.def.activeCells)
    }

    fun render(renderer: BatchTiledMapRenderer) {
        updateVisibleObjects(renderer.viewBounds)
        visibleIds.forEach { id -> if (!hidden[id]) sprites[id].render(renderer) }
    }

    fun setHidden(id: Int, isHidden: Boolean) {
        if (id in hidden.indices) hidden[id] = isHidden
    }

    fun showAll() = hidden.fill(false)

    fun objectCoverage(viewBounds: Rectangle): Float {
        updateVisibleObjects(viewBounds)

        val tileSize = TILE_SIZE.toInt()
        val cols = (viewBounds.width / tileSize).toInt() + 1
        val rows = (viewBounds.height / tileSize).toInt() + 1
        if (cols <= 0 || rows <= 0) return 0f
        val grid = Array(cols) { BooleanArray(rows) }

        for (sprite in visibleSprites) {
            val rect = sprite.worldBounds() ?: continue
            val rx0 = maxOf(rect.x, viewBounds.x)
            val ry0 = maxOf(rect.y, viewBounds.y)
            val rx1 = minOf(rect.x + rect.width, viewBounds.x + viewBounds.width)
            val ry1 = minOf(rect.y + rect.height, viewBounds.y + viewBounds.height)
            if (rx0 >= rx1 || ry0 >= ry1) continue
            val gx0 = ((rx0 - viewBounds.x) / tileSize).toInt().coerceIn(0, cols - 1)
            val gy0 = ((ry0 - viewBounds.y) / tileSize).toInt().coerceIn(0, rows - 1)
            val gx1 = ((rx1 - viewBounds.x) / tileSize).toInt().coerceIn(0, cols - 1)
            val gy1 = ((ry1 - viewBounds.y) / tileSize).toInt().coerceIn(0, rows - 1)
            for (gx in gx0..gx1) for (gy in gy0..gy1) grid[gx][gy] = true
        }

        var filled = 0
        for (col in grid) for (b in col) if (b) filled++
        return filled.toFloat() / (cols * rows)
    }

    private fun updateVisibleObjects(viewBounds: Rectangle) {
        val startY = (viewBounds.y / TILE_SIZE).toInt() - FRUSTUM_PADDING_TILES
        val endY = ((viewBounds.y + viewBounds.height) / TILE_SIZE).toInt() + FRUSTUM_PADDING_TILES
        val startX = (viewBounds.x / TILE_SIZE).toInt() - FRUSTUM_PADDING_TILES
        val endX = ((viewBounds.x + viewBounds.width) / TILE_SIZE).toInt() + FRUSTUM_PADDING_TILES

        if (startX == lastStartX && startY == lastStartY && endX == lastEndX && endY == lastEndY) {
            return
        }

        visibleSprites.clear()
        visibleIds.clear()
        val visibleIndices = visibleIds
        for (y in startY..endY) {
            val column = spritesIndices[y] ?: continue
            for (x in startX..endX) {
                val indices = column[x] ?: continue
                visibleIndices.addAll(indices)
            }
        }
        visibleIndices.sort()
        visibleIndices.forEach { id -> visibleSprites.add(sprites[id]) }

        lastStartX = startX
        lastStartY = startY
        lastEndX = endX
        lastEndY = endY
    }

    private fun buildSpatialIndex(objects: List<H3mObject>): Map<Int, Map<Int, List<Int>>> {
        val index = mutableMapOf<Int, MutableMap<Int, MutableList<Int>>>()
        objects
            .forEachIndexed { i, obj ->
                index
                    .getOrPut(obj.y) { mutableMapOf() }
                    .getOrPut(obj.x) { mutableListOf() }
                    .add(i)
            }
        return index
    }

    companion object {
        val MONSTER_TYPES = setOf(
            H3mObjectType.MONSTER,
            H3mObjectType.RANDOM_MONSTER,
            H3mObjectType.RANDOM_MONSTER_L1,
            H3mObjectType.RANDOM_MONSTER_L2,
            H3mObjectType.RANDOM_MONSTER_L3,
            H3mObjectType.RANDOM_MONSTER_L4,
            H3mObjectType.RANDOM_MONSTER_L5,
            H3mObjectType.RANDOM_MONSTER_L6,
            H3mObjectType.RANDOM_MONSTER_L7,
        )
    }
}
