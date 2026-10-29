package com.homm3.livewallpaper.core.heroes

import com.homm3.livewallpaper.core.heroes.Footprint.Offset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FootprintTest {
    private val allOpen = listOf(-1, 0xFFFF)

    @Test
    fun `a fully passable mask blocks nothing`() {
        assertTrue(Footprint.blockedOffsets(allOpen).isEmpty())
    }

    @Test
    fun `clearing the anchor bit blocks the anchor tile`() {
        // Row 5 is the high byte of the short; bit 7 of it is the anchor column.
        val mask = listOf(-1, 0x7FFF)
        assertEquals(listOf(Offset(0, 0)), Footprint.blockedOffsets(mask))
    }

    @Test
    fun `a 2x2 building blocks the tiles left of and above its anchor`() {
        // Rows 4 and 5 have bits 6 and 7 clear.
        val mask = listOf(-1, 0x3F3F)
        assertEquals(
            setOf(Offset(-1, -1), Offset(0, -1), Offset(-1, 0), Offset(0, 0)),
            Footprint.blockedOffsets(mask).toSet(),
        )
    }

    @Test
    fun `a blocked tile in the first int maps to an upper row`() {
        // Row 0 (lowest byte of the int), bit 0: seven tiles left, five up.
        val mask = listOf(-1 xor 1, 0xFFFF)
        assertEquals(listOf(Offset(-7, -5)), Footprint.blockedOffsets(mask))
    }

    @Test
    fun `the visit tile is read from the active mask`() {
        // Classic monster template: visitable at the anchor.
        assertEquals(listOf(Offset(0, 0)), Footprint.visitOffsets(listOf(0, 0x8000)))
        // A mine-like entrance one tile left of the anchor.
        assertEquals(listOf(Offset(-1, 0)), Footprint.visitOffsets(listOf(0, 0x4000)))
    }

    @Test
    fun `short masks are ignored`() {
        assertTrue(Footprint.visitOffsets(listOf(0)).isEmpty())
    }
}
