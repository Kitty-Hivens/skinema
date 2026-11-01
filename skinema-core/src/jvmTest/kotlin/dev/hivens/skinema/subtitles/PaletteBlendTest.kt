package dev.hivens.skinema.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteBlendTest {

    private fun pixel(rgba: ByteArray, width: Int, x: Int, y: Int): List<Int> {
        val i = (y * width + x) * 4
        return (0..3).map { rgba[i + it].toInt() and 0xFF }
    }

    @Test
    fun `argb palette entries land premultiplied`() {
        // Index 1 = opaque red, index 2 = half-transparent white.
        val palette = intArrayOf(0x00000000, 0xFFFF0000.toInt(), 0x80FFFFFF.toInt())
        val indices = byteArrayOf(1, 2)
        val out = paletteToRgba(indices, linesize = 2, width = 2, height = 1, palette = palette)
        assertEquals(listOf(255, 0, 0, 255), pixel(out, 2, 0, 0))
        assertEquals(listOf(128, 128, 128, 128), pixel(out, 2, 1, 0), "premultiplied at alpha 0x80")
    }

    @Test
    fun `transparent and out-of-range indices stay clear`() {
        val palette = intArrayOf(0x00000000, 0xFF00FF00.toInt())
        val indices = byteArrayOf(0, 9, 1)
        val out = paletteToRgba(indices, linesize = 3, width = 3, height = 1, palette = palette)
        assertEquals(listOf(0, 0, 0, 0), pixel(out, 3, 0, 0), "alpha 0 contributes nothing")
        assertEquals(listOf(0, 0, 0, 0), pixel(out, 3, 1, 0), "a damaged index reads transparent")
        assertEquals(listOf(0, 255, 0, 255), pixel(out, 3, 2, 0))
    }

    @Test
    fun `rows walk linesize and the last row needs no padding`() {
        // 2x2 image at linesize 5; the buffer is exactly 5*(2-1)+2 long.
        val palette = intArrayOf(0x00000000, 0xFF0000FF.toInt())
        val indices = ByteArray(5 + 2)
        indices[0] = 1
        indices[5 + 1] = 1
        val out = paletteToRgba(indices, linesize = 5, width = 2, height = 2, palette = palette)
        assertEquals(listOf(0, 0, 255, 255), pixel(out, 2, 0, 0))
        assertEquals(listOf(0, 0, 0, 0), pixel(out, 2, 1, 0))
        assertEquals(listOf(0, 0, 255, 255), pixel(out, 2, 1, 1))
    }
}
