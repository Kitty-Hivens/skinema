package dev.hivens.skinema.skiko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The overlay holder had no test at all -- coverage said zero of its
 * twenty-five lines had ever run, which is the whole path that puts
 * subtitle pixels on screen for a skiko consumer. What it owns is native
 * memory, so the rules worth holding are about lifetime: nothing outlives the
 * borrow that names it, and nothing is freed while a borrow still does.
 */
class SubtitleOverlayImageTest {

    private fun patch(x: Int, y: Int, w: Int, h: Int, alpha: Int = 255) =
        SubtitleOverlayImage.PatchPixels(x, y, w, h, ByteArray(w * h * 4) { i -> if (i % 4 == 3) alpha.toByte() else 0 })

    @Test
    fun `update places every patch where the consumer put it`() {
        SubtitleOverlayImage().use { overlay ->
            overlay.update(listOf(patch(10, 20, 4, 2), patch(30, 40, 2, 2)))
            val placed = overlay.images
            assertEquals(2, placed.size)
            assertEquals(10, placed[0].x)
            assertEquals(20, placed[0].y)
            assertEquals(4, placed[0].image.width)
            assertEquals(2, placed[0].image.height)
            assertEquals(30, placed[1].x)
            assertEquals(40, placed[1].y)
        }
    }

    /** What [SubtitleOverlayImage.update] hands back stands until the next publish. */
    @Test
    fun `an update releases the generation it replaces`() {
        SubtitleOverlayImage().use { overlay ->
            val replaced = overlay.update(listOf(patch(0, 0, 2, 2)))
            assertFalse(replaced.single().image.isClosed, "the overlay just published is the current one")
            val fresh = overlay.update(listOf(patch(5, 5, 2, 2)))
            assertTrue(replaced.single().image.isClosed, "the previous overlay's native image must be released")
            assertFalse(fresh.single().image.isClosed, "the new one must be live")
        }
    }

    @Test
    fun `an empty update is the clear`() {
        SubtitleOverlayImage().use { overlay ->
            val shown = overlay.update(listOf(patch(0, 0, 2, 2)))
            overlay.update(emptyList())
            assertTrue(overlay.images.isEmpty(), "nothing must remain to draw")
            assertTrue(shown.single().image.isClosed, "clearing must release, not just forget")
        }
    }

    /**
     * The safety half, and the reason this stopped closing on the spot: the
     * thread that draws may still be painting with the generation it took,
     * and freeing it there is a native crash rather than a wrong picture.
     *
     * This is what lets [SubtitleOverlayImage.update] run wherever the frame
     * raster runs. Before it, the guide's advice to raster off the drawing
     * thread was true of frames and fatal for overlays.
     */
    @Test
    fun `the overlay the drawing side took survives the ones published behind it`() {
        SubtitleOverlayImage().use { overlay ->
            overlay.update(listOf(patch(0, 0, 2, 2)))
            val held = overlay.images.single().image
            val behind = (1..50).map { overlay.update(listOf(patch(1, 1, 2, 2))) }

            assertFalse(held.isClosed, "freeing the drawer's patches is a free under a draw")
            assertEquals(
                2,
                held.width,
                "the borrowed image must still name its own pixels",
            )
            assertTrue(
                behind.dropLast(1).all { it.single().image.isClosed },
                "everything nobody borrowed must be freed",
            )
            assertFalse(behind.last().single().image.isClosed, "the current overlay is not superseded")
        }
    }

    /** And the borrow moves with the reads rather than accumulating. */
    @Test
    fun `taking the next overlay releases the one before it`() {
        SubtitleOverlayImage().use { overlay ->
            overlay.update(listOf(patch(0, 0, 2, 2)))
            val first = overlay.images.single().image
            overlay.update(listOf(patch(5, 5, 2, 2)))
            assertFalse(first.isClosed, "still the drawer's, until it takes another")

            overlay.images
            overlay.update(listOf(patch(9, 9, 2, 2)))
            assertTrue(first.isClosed, "the borrow ended when the drawer took the next one")
        }
    }

    /**
     * A caller that never draws must not accumulate either, which is the
     * failure the frame holder was carrying and this one would have grown the
     * moment it stopped closing eagerly.
     */
    @Test
    fun `a caller that never reads accumulates nothing`() {
        SubtitleOverlayImage().use { overlay ->
            val published = (1..50).map { overlay.update(listOf(patch(0, 0, 2, 2))) }
            assertTrue(
                published.dropLast(1).all { it.single().image.isClosed },
                "every superseded generation must be freed",
            )
            assertFalse(published.last().single().image.isClosed, "the current overlay is not superseded")
        }
    }

    /**
     * Closing frees and leaves the object usable, which is the half that does
     * NOT match the frame holder. Turning subtitles off is a reason to drop
     * the pixels while the surface lives on, and the re-selection after it has
     * to be able to publish again -- the Compose surface does exactly that.
     */
    @Test
    fun `close releases everything and leaves the overlay usable`() {
        val overlay = SubtitleOverlayImage()
        overlay.update(listOf(patch(0, 0, 2, 2)))
        val borrowed = overlay.images.single().image
        val current = overlay.update(listOf(patch(4, 4, 2, 2)))

        overlay.close()

        assertTrue(borrowed.isClosed, "a borrow outstanding at teardown is still freed")
        assertTrue(current.all { it.image.isClosed })
        assertTrue(overlay.images.isEmpty())
        // A consumer closing a player twice must not reach a freed pointer.
        overlay.close()

        val again = overlay.update(listOf(patch(7, 7, 2, 2)))
        assertFalse(again.single().image.isClosed, "a re-selected track must be able to draw again")
        assertEquals(1, overlay.images.size)
        overlay.close()
    }
}
