package dev.hivens.skinema.skiko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VideoFrameImageTest {

    private fun solidRgba(width: Int, height: Int, r: Int, g: Int, b: Int, a: Int): ByteArray {
        val bytes = ByteArray(width * height * 4)
        for (i in bytes.indices step 4) {
            bytes[i] = r.toByte()
            bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte()
            bytes[i + 3] = a.toByte()
        }
        return bytes
    }

    @Test
    fun `update produces an image carrying the pixels`() {
        VideoFrameImage().use { holder ->
            val image = holder.update(2, 2, solidRgba(2, 2, 255, 0, 0, 255))
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            assertSame(image, holder.image)
            val pixmap = image.peekPixels()!!
            val argb = pixmap.getColor(0, 0)
            assertEquals(0xFFFF0000.toInt(), argb, "expected opaque red, got ${argb.toUInt().toString(16)}")
        }
    }

    @Test
    fun `update closes the previous frame's image`() {
        VideoFrameImage().use { holder ->
            val first = holder.update(2, 2, solidRgba(2, 2, 255, 0, 0, 255))
            val second = holder.update(2, 2, solidRgba(2, 2, 0, 255, 0, 255))
            assertTrue(first.isClosed, "the replaced image must be closed, not left to the finalizer")
            assertFalse(second.isClosed)
        }
    }

    @Test
    fun `update copies the pixels -- the source buffer is reusable`() {
        VideoFrameImage().use { holder ->
            val buffer = solidRgba(2, 2, 255, 0, 0, 255)
            val image = holder.update(2, 2, buffer)
            buffer.fill(0)
            assertEquals(0xFFFF0000.toInt(), image.peekPixels()!!.getColor(1, 1))
        }
    }

    @Test
    fun `geometry changes between updates are fine`() {
        VideoFrameImage().use { holder ->
            holder.update(2, 2, solidRgba(2, 2, 0, 0, 255, 255))
            val image = holder.update(4, 2, solidRgba(4, 2, 0, 255, 0, 255))
            assertEquals(4, image.width)
        }
    }

    @Test
    fun `close disposes the current image and clears the reference`() {
        val holder = VideoFrameImage()
        val image = holder.update(2, 2, solidRgba(2, 2, 255, 255, 255, 255))
        holder.close()
        assertTrue(image.isClosed)
        assertNull(holder.image)
    }
}
