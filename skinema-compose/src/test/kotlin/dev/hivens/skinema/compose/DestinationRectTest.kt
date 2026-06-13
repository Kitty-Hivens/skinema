package dev.hivens.skinema.compose

import org.jetbrains.skia.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationRectTest {

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, absoluteTolerance = 0.1f)
        assertEquals(expected.top, actual.top, absoluteTolerance = 0.1f)
        assertEquals(expected.right, actual.right, absoluteTolerance = 0.1f)
        assertEquals(expected.bottom, actual.bottom, absoluteTolerance = 0.1f)
    }

    @Test
    fun `matching aspect fills the bounds exactly under both modes`() {
        for (scale in VideoScale.entries) {
            assertRect(
                Rect.makeWH(200f, 100f),
                destinationRect(20f, 10f, 200f, 100f, scale),
            )
        }
    }

    @Test
    fun `cover crops the wider video, centered horizontally`() {
        // 32:9 video into a 16:9 box: height fills, width doubles and hangs
        // half a box off each side.
        assertRect(
            Rect.makeXYWH(-160f, 0f, 640f, 90f),
            destinationRect(320f, 45f, 320f, 90f, VideoScale.Cover),
        )
    }

    @Test
    fun `fit letterboxes the wider video, centered vertically`() {
        assertRect(
            Rect.makeXYWH(0f, 22.5f, 320f, 45f),
            destinationRect(320f, 45f, 320f, 90f, VideoScale.Fit),
        )
    }

    @Test
    fun `cover crops the taller video, centered vertically`() {
        // 9:16 portrait into 16:9: width fills, height overflows evenly.
        assertRect(
            Rect.makeXYWH(0f, -239.4f, 320f, 568.9f),
            destinationRect(90f, 160f, 320f, 90f, VideoScale.Cover),
        )
    }

    @Test
    fun `fit pillarboxes the taller video, centered horizontally`() {
        assertRect(
            Rect.makeXYWH(134.7f, 0f, 50.6f, 90f),
            destinationRect(90f, 160f, 320f, 90f, VideoScale.Fit),
        )
    }

    @Test
    fun `subtitle patches map uniformly onto the destination rect`() {
        // Canvas 640x480 onto a 320x240 dst at (40, 10): half scale.
        val dst = Rect.makeXYWH(40f, 10f, 320f, 240f)
        assertRect(
            Rect.makeXYWH(40f + 50f, 10f + 100f, 60f, 20f),
            subtitleDrawRect(dst, 640, 480, x = 100, y = 200, width = 120, height = 40),
        )
        // A degenerate canvas draws nothing rather than dividing by zero.
        assertRect(Rect.makeWH(0f, 0f), subtitleDrawRect(dst, 0, 0, 1, 1, 1, 1))
    }

    @Test
    fun `quarter turns swap the displayed dimensions`() {
        assertEquals(48f to 64f, displayedSize(64f, 48f, 90))
        assertEquals(48f to 64f, displayedSize(64f, 48f, 270))
        assertEquals(64f to 48f, displayedSize(64f, 48f, 0))
        assertEquals(64f to 48f, displayedSize(64f, 48f, 180))
    }

    @Test
    fun `the image rect under a quarter turn swaps sides about the center`() {
        // Displayed rect 80x200 at (60,20): under the canvas rotation the
        // image's natural orientation is 200x80 around the same center.
        val dst = Rect.makeXYWH(60f, 20f, 80f, 200f)
        assertRect(
            Rect.makeXYWH(0f, 80f, 200f, 80f),
            imageDrawRect(dst, 90),
        )
        assertRect(dst, imageDrawRect(dst, 180))
        assertRect(dst, imageDrawRect(dst, 0))
    }
}
