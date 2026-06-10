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
}
