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
            val image = holder.update(2, 2, solidRgba(2, 2, 255, 0, 0, 255))!!
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            assertSame(image, holder.image)
            val pixmap = image.peekPixels()!!
            val argb = pixmap.getColor(0, 0)
            assertEquals(0xFFFF0000.toInt(), argb, "expected opaque red, got ${argb.toUInt().toString(16)}")
        }
    }

    /**
     * The replaced image is retired, not closed, because the thread that
     * draws may still be holding it -- the raster runs somewhere else now.
     * Closing it there would be a free under a draw; leaving it to the
     * finalizer is the leak this class exists to avoid. So it is handed over,
     * and the drawing side closes it when it says it is past it.
     */
    @Test
    fun `a replaced image is retired until the drawing side reclaims it`() {
        VideoFrameImage().use { holder ->
            val first = holder.update(2, 2, solidRgba(2, 2, 255, 0, 0, 255))!!
            val second = holder.update(2, 2, solidRgba(2, 2, 0, 255, 0, 255))!!
            assertFalse(first.isClosed, "a draw may still be on the replaced image")
            assertEquals(1, holder.pending)

            holder.reclaim()
            assertTrue(first.isClosed, "reclaim is what closes it")
            assertFalse(second.isClosed, "the current image is not retired")
            assertEquals(0, holder.pending)
        }
    }

    /**
     * A raster that was already in flight when the surface went away has to
     * come back with nothing rather than publish into a closed session.
     */
    @Test
    fun `update after close produces nothing`() {
        val holder = VideoFrameImage()
        holder.close()
        assertNull(holder.update(2, 2, solidRgba(2, 2, 255, 0, 0, 255)))
        assertNull(holder.image)
    }

    @Test
    fun `update copies the pixels -- the source buffer is reusable`() {
        VideoFrameImage().use { holder ->
            val buffer = solidRgba(2, 2, 255, 0, 0, 255)
            val image = holder.update(2, 2, buffer)!!
            buffer.fill(0)
            assertEquals(0xFFFF0000.toInt(), image.peekPixels()!!.getColor(1, 1))
        }
    }

    @Test
    fun `geometry changes between updates are fine`() {
        VideoFrameImage().use { holder ->
            holder.update(2, 2, solidRgba(2, 2, 0, 0, 255, 255))!!
            val image = holder.update(4, 2, solidRgba(4, 2, 0, 255, 0, 255))!!
            assertEquals(4, image.width)
        }
    }

    @Test
    fun `close disposes the current image and clears the reference`() {
        val holder = VideoFrameImage()
        val image = holder.update(2, 2, solidRgba(2, 2, 255, 255, 255, 255))!!
        holder.close()
        assertTrue(image.isClosed)
        assertNull(holder.image)
    }
    /**
     * The half of the contract a caller can drop in silence.
     *
     * update() alone looks like it works -- the picture is right -- while every
     * superseded image stays alive in native memory that no heap profiler shows
     * and no collector can take, because the queue holds a strong reference.
     * A real consumer did exactly this; measured at 1080p it took resident
     * memory from 250 MB to 1796 MB over two hundred frames.
     *
     * So the class says so once. Asserted in both directions, because a warning
     * that fires for a correct caller is worse than none.
     */
    @Test
    fun `a backlog nobody reclaims is reported once, and a drained one is not`() {
        val w = 4
        val h = 4
        val rgba = ByteArray(w * h * 4)

        VideoFrameImage().use { img ->
            repeat(80) { img.update(w, h, rgba) }
            assertTrue(
                img.warnedAboutBacklog,
                "a caller that never reclaims must be told, pending=${img.pending}",
            )
        }

        VideoFrameImage().use { img ->
            repeat(80) { img.update(w, h, rgba); img.reclaim() }
            assertEquals(0, img.pending, "a drained backlog must stay empty")
            assertFalse(img.warnedAboutBacklog, "a caller that reclaims must not be warned")
        }
    }
}
