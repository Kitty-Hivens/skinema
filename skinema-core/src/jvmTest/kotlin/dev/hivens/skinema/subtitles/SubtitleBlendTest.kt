package dev.hivens.skinema.subtitles

import dev.hivens.skinema.ass.AssPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubtitleBlendTest {

    private fun solid(
        width: Int,
        height: Int,
        stride: Int = width,
        coverage: Int = 255,
        color: Int,
        dstX: Int = 0,
        dstY: Int = 0,
    ): AssPatch {
        // The guaranteed allocation: the last row is NOT padded to stride.
        val alpha = ByteArray(stride * (height - 1) + width)
        for (row in 0 until height) {
            for (col in 0 until width) {
                alpha[row * stride + col] = coverage.toByte()
            }
        }
        return AssPatch(width, height, stride, alpha, color, dstX, dstY)
    }

    private fun pixel(patch: BlendedPatch, x: Int, y: Int): List<Int> {
        val i = (y * patch.width + x) * 4
        return (0..3).map { patch.rgba[i + it].toInt() and 0xFF }
    }

    @Test
    fun `an empty render blends to nothing`() {
        assertNull(blendAssPatches(emptyList()))
    }

    @Test
    fun `the color's low byte is inverted alpha`() {
        // 0xRRGGBBAA with AA=0 -- fully opaque red.
        val opaque = blendAssPatches(listOf(solid(2, 2, color = 0xFF000000.toInt())))!!
        assertEquals(listOf(255, 0, 0, 255), pixel(opaque, 0, 0), "AA=0 must mean opaque")

        // AA=255 -- fully transparent: contributes nothing.
        val transparent = blendAssPatches(listOf(solid(2, 2, color = 0xFF0000FF.toInt())))!!
        assertEquals(listOf(0, 0, 0, 0), pixel(transparent, 0, 0), "AA=255 must mean invisible")
    }

    @Test
    fun `output is premultiplied`() {
        // Half coverage of opaque white: every channel carries the alpha.
        val half = blendAssPatches(listOf(solid(1, 1, coverage = 128, color = 0xFFFFFF00.toInt())))!!
        val (r, g, b, a) = pixel(half, 0, 0)
        assertEquals(a, r, "premultiplied red equals alpha for white")
        assertEquals(a, g)
        assertEquals(a, b)
        assertTrue(a in 126..129, "half coverage, got $a")
    }

    @Test
    fun `stride padding is never read`() {
        val patch = solid(2, 3, stride = 8, color = 0xFF000000.toInt())
        // Poison every padding byte; a blend that reads past width would
        // pick these up as coverage.
        for (row in 0 until 2) {
            for (col in 2 until 8) {
                patch.alpha[row * 8 + col] = 0xEE.toByte()
            }
        }
        val out = blendAssPatches(listOf(patch))!!
        assertEquals(2, out.width, "the bbox is the image, not its stride")
        assertEquals(listOf(255, 0, 0, 255), pixel(out, 1, 2))
    }

    @Test
    fun `the exact last-row allocation is enough`() {
        // stride > width and the array sized exactly stride*(h-1)+w: an
        // implementation that walks stride*h overreads and throws.
        val patch = solid(3, 4, stride = 16, color = 0x00FF0000)
        assertEquals(16 * 3 + 3, patch.alpha.size)
        val out = blendAssPatches(listOf(patch))!!
        assertEquals(listOf(0, 255, 0, 255), pixel(out, 2, 3))
    }

    @Test
    fun `later images composite over earlier ones`() {
        // libass paints list order bottom-to-top: an opaque green square
        // over an opaque red one must win where they overlap.
        val red = solid(2, 1, color = 0xFF000000.toInt(), dstX = 0)
        val green = solid(2, 1, color = 0x00FF0000, dstX = 1)
        val out = blendAssPatches(listOf(red, green))!!
        assertEquals(3, out.width)
        assertEquals(listOf(255, 0, 0, 255), pixel(out, 0, 0))
        assertEquals(listOf(0, 255, 0, 255), pixel(out, 1, 0), "the later image wins the overlap")
        assertEquals(listOf(0, 255, 0, 255), pixel(out, 2, 0))
    }

    @Test
    fun `the bounding box unions disjoint images`() {
        val a = solid(2, 2, color = 0xFF000000.toInt(), dstX = 10, dstY = 20)
        val b = solid(2, 2, color = 0x00FF0000, dstX = 30, dstY = 5)
        val out = blendAssPatches(listOf(a, b))!!
        assertEquals(10, out.x)
        assertEquals(5, out.y)
        assertEquals(22, out.width)
        assertEquals(17, out.height)
        assertEquals(listOf(0, 0, 0, 0), pixel(out, 5, 10), "the gap stays transparent")
    }

    @Test
    fun `a matching reuse array is taken and cleared`() {
        val reuse = ByteArray(2 * 2 * 4) { 0x55 }
        val out = blendAssPatches(listOf(solid(2, 2, color = 0xFF000000.toInt())), reuse)!!
        assertSame(reuse, out.rgba, "a size-matched array must be reused")
        assertEquals(listOf(255, 0, 0, 255), pixel(out, 1, 1), "stale contents must not bleed through")
    }
}
