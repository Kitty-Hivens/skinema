package dev.hivens.skinema.skiko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
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

    private fun red(w: Int = 2, h: Int = 2) = solidRgba(w, h, 255, 0, 0, 255)
    private fun green(w: Int = 2, h: Int = 2) = solidRgba(w, h, 0, 255, 0, 255)
    private fun blue(w: Int = 2, h: Int = 2) = solidRgba(w, h, 0, 0, 255, 255)

    @Test
    fun `update produces an image carrying the pixels`() {
        VideoFrameImage().use { holder ->
            val image = holder.update(2, 2, red())!!
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            assertSame(image, holder.image)
            val pixmap = image.peekPixels()!!
            val argb = pixmap.getColor(0, 0)
            assertEquals(0xFFFF0000.toInt(), argb, "expected opaque red, got ${argb.toUInt().toString(16)}")
        }
    }

    /**
     * The safety half of the contract, and the reason nothing is closed
     * eagerly: the thread that draws may still be painting with the image it
     * took, and freeing it under that draw is a native crash rather than a
     * wrong picture.
     *
     * The image is read at the end rather than merely inspected for a flag --
     * a reference that survives but no longer names live pixels is the same
     * defect one step later.
     */
    @Test
    fun `the image the drawing side took survives every frame published behind it`() {
        VideoFrameImage().use { holder ->
            holder.update(2, 2, blue())
            val held = holder.image!!
            repeat(200) { holder.update(2, 2, green()) }

            assertFalse(held.isClosed, "freeing the drawer's image is a free under a draw")
            assertEquals(1, holder.pending, "and nothing else is kept for it")
            assertEquals(
                0xFF0000FF.toInt(),
                held.peekPixels()!!.getColor(0, 0),
                "the borrowed image must still name its own pixels",
            )
        }
    }

    /**
     * The half this class did not have, and the whole of issue #65.
     *
     * Retiring into a queue nothing drained gave a caller who never reclaimed
     * no error, no ceiling and no signal beyond process size -- the queue held
     * a strong reference, so the images were neither freed nor collectable and
     * a heap profiler showed nothing. Measured at 1080p: two hundred frames
     * took resident memory from 250 MB to 1796 MB.
     *
     * Two hundred frames here for that reason, against a bound of one.
     */
    @Test
    fun `a caller that never reclaims accumulates nothing`() {
        VideoFrameImage().use { holder ->
            val published = (1..200).map { holder.update(2, 2, red())!! }

            assertEquals(0, holder.pending, "nothing is spoken for when nobody has drawn")
            assertTrue(
                published.dropLast(1).all { it.isClosed },
                "every superseded image must be freed, ${published.count { !it.isClosed }} were not",
            )
            assertFalse(published.last().isClosed, "the current image is not superseded")
        }
    }

    /** What [VideoFrameImage.update] hands back is the current image until the next publish. */
    @Test
    fun `what update returned lasts until the next update`() {
        VideoFrameImage().use { holder ->
            val first = holder.update(2, 2, red())!!
            assertFalse(first.isClosed, "the image just published is the current one")
            val second = holder.update(2, 2, green())!!
            assertTrue(first.isClosed, "and the next publish is what ends it")
            assertFalse(second.isClosed)
        }
    }

    /** And the drawing side's borrow moves with its reads rather than accumulating. */
    @Test
    fun `taking the next image releases the one before it`() {
        VideoFrameImage().use { holder ->
            holder.update(2, 2, red())
            val first = holder.image!!
            holder.update(2, 2, green())
            assertFalse(first.isClosed, "still the drawer's, until it takes another")

            val second = holder.image!!
            assertNotSame(first, second)
            holder.update(2, 2, blue())

            assertTrue(first.isClosed, "the borrow ended when the drawer took the next one")
            assertFalse(second.isClosed, "and moved onto that one")
            assertEquals(1, holder.pending)
        }
    }

    /**
     * [VideoFrameImage.reclaim] stops being required and stays worth calling:
     * it frees the borrow at the start of a draw rather than at the next
     * publish, which is one frame of native memory held for one frame less.
     */
    @Test
    fun `reclaim frees the borrow without waiting for a publish`() {
        VideoFrameImage().use { holder ->
            holder.update(2, 2, red())
            val first = holder.image!!
            holder.update(2, 2, green())
            assertEquals(1, holder.pending)

            holder.reclaim()

            assertTrue(first.isClosed, "reclaim is the drawing side saying it is past it")
            assertEquals(0, holder.pending)
        }
    }

    /**
     * A raster that was already in flight when the surface went away has to
     * come back with nothing rather than publish into a closed session -- and
     * the copy it had already made must not be dropped on the floor, since the
     * copy happens outside the lock now.
     */
    @Test
    fun `update after close produces nothing`() {
        val holder = VideoFrameImage()
        holder.close()
        assertNull(holder.update(2, 2, red()))
        assertNull(holder.image)
    }

    @Test
    fun `update copies the pixels -- the source buffer is reusable`() {
        VideoFrameImage().use { holder ->
            val buffer = red()
            val image = holder.update(2, 2, buffer)!!
            buffer.fill(0)
            assertEquals(0xFFFF0000.toInt(), image.peekPixels()!!.getColor(1, 1))
        }
    }

    @Test
    fun `geometry changes between updates are fine`() {
        VideoFrameImage().use { holder ->
            holder.update(2, 2, blue())
            val image = holder.update(4, 2, green(4, 2))!!
            assertEquals(4, image.width)
        }
    }

    @Test
    fun `close disposes the current image and the borrow, and clears the reference`() {
        val holder = VideoFrameImage()
        holder.update(2, 2, red())
        val borrowed = holder.image!!
        val current = holder.update(2, 2, green())!!

        holder.close()

        assertTrue(borrowed.isClosed, "a borrow outstanding at teardown is still freed")
        assertTrue(current.isClosed)
        assertEquals(0, holder.pending)
        assertNull(holder.image)
    }
}
