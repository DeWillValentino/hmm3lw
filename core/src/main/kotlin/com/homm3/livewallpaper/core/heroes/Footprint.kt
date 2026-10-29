package com.homm3.livewallpaper.core.heroes

/**
 * Reads the 6x8 tile masks of an object template (H3mDef.passableCells / activeCells).
 *
 * A mask is six bytes, one per row from the top; the map stores it as an Int (rows 0-3) and a Short (rows 4-5),
 * little-endian. Row 5 is the object's anchor row, bit 7 of a row is the anchor column, so bit `b` of row `r` is the
 * tile `(b - 7, r - 5)` away from the object's position. In the passable mask a clear bit blocks the tile; in the
 * active mask a set bit makes it visitable.
 */
object Footprint {
    private const val ROWS = 6
    private const val COLUMNS = 8
    private const val BYTES_IN_FIRST_VALUE = 4
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF

    data class Offset(val dx: Int, val dy: Int)

    /** Offsets of the tiles the object blocks. */
    fun blockedOffsets(passableCells: List<Int>): List<Offset> = offsets(passableCells, bitSetMeans = false)

    /** Offsets of the tiles that visit the object. */
    fun visitOffsets(activeCells: List<Int>): List<Offset> = offsets(activeCells, bitSetMeans = true)

    private fun offsets(mask: List<Int>, bitSetMeans: Boolean): List<Offset> {
        if (mask.size < 2) return emptyList()
        val result = mutableListOf<Offset>()
        for (row in 0 until ROWS) {
            val rowBits = rowByte(mask, row)
            for (bit in 0 until COLUMNS) {
                val isSet = (rowBits ushr bit) and 1 == 1
                if (isSet == bitSetMeans) result += Offset(bit - (COLUMNS - 1), row - (ROWS - 1))
            }
        }
        return result
    }

    private fun rowByte(mask: List<Int>, row: Int): Int =
        if (row < BYTES_IN_FIRST_VALUE) {
            (mask[0] ushr (row * BYTE_BITS)) and BYTE_MASK
        } else {
            (mask[1] ushr ((row - BYTES_IN_FIRST_VALUE) * BYTE_BITS)) and BYTE_MASK
        }
}
