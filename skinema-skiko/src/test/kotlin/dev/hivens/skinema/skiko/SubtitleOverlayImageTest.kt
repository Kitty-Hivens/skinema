package dev.hivens.skinema.skiko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The overlay holder had no test at all -- coverage said zero of its
 * twenty-five lines had ever run, which is the whole path that puts
 * subtitle pixels on screen for a skiko consumer. What it owns is native
 * memory, so the rules worth holding are about lifetime: an update closes
 * what it replaces, and close leaves nothing behind.
 */
class SubtitleOverlayImageTest {

    private fun patch(x: Int, y: Int, w: Int, h: Int, alpha: Int = 255) =
        SubtitleOverlayImage.PatchPixels(x, y, w, h, ByteArray(w * h * 4) { i -> if (i % 4 == 3) alpha.toByte() else 0 })

    @Test
    fun `update places every patch where the consumer put it`() {
        SubtitleOverlayImage().use { overlay ->
            overlay.update(listOf(patch(10, 20, 4, 2), patch(30, 40, 2, 2)))
            assertEquals(2, overlay.images.size)
            val first = overlay.images[0]
            assertEquals(10, first.x)
            assertEquals(20, first.y)
            assertEquals(4, first.image.width)
            assertEquals(2, first.image.height)
            assertEquals(30, overlay.images[1].x)
            assertEquals(40, overlay.images[1].y)
        }
    }

    @Test
    fun `an update closes the images it replaces`() {
        SubtitleOverlayImage().use { overlay ->
            overlay.update(listOf(patch(0, 0, 2, 2)))
            val replaced = overlay.images.single().image
            overlay.update(listOf(patch(5, 5, 2, 2)))
            assertTrue(replaced.isClosed, "the previous overlay's native image must be released")
            assertFalse(overlay.images.single().image.isClosed, "the new one must be live")
        }
    }

    @Test
    fun `an empty update is the clear`() {
        SubtitleOverlayImage().use { overlay ->
            overlay.update(listOf(patch(0, 0, 2, 2)))
            val cleared = overlay.images.single().image
            overlay.update(emptyList())
            assertTrue(overlay.images.isEmpty(), "nothing must remain to draw")
            assertTrue(cleared.isClosed, "clearing must release, not just forget")
        }
    }

    @Test
    fun `close releases the current overlay and can run twice`() {
        val overlay = SubtitleOverlayImage()
        overlay.update(listOf(patch(0, 0, 2, 2), patch(4, 4, 2, 2)))
        val held = overlay.images.map { it.image }
        overlay.close()
        assertTrue(held.all { it.isClosed }, "close must release every image it held")
        assertTrue(overlay.images.isEmpty())
        // A consumer closing a player twice must not reach a freed pointer.
        overlay.close()
    }
}
