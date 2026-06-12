package dev.hivens.skinema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {

    private var fakeNow = 0L
    private val clock = PlaybackClock { fakeNow }

    @Test
    fun `media time advances with wall time while running`() {
        clock.start()
        fakeNow = 500
        assertEquals(500, clock.mediaNanos())
    }

    @Test
    fun `starts paused at zero`() {
        assertTrue(clock.isPaused)
        fakeNow = 1_000
        assertEquals(0, clock.mediaNanos())
    }

    @Test
    fun `pause freezes and resume continues without a jump`() {
        clock.start()
        fakeNow = 300
        clock.pause()
        fakeNow = 900
        assertEquals(300, clock.mediaNanos(), "paused media time must not advance")
        clock.resume()
        fakeNow = 1_000
        assertEquals(400, clock.mediaNanos(), "the paused gap must not count as playback")
    }

    @Test
    fun `seek jumps media time and keeps running state`() {
        clock.start()
        fakeNow = 100
        clock.seek(5_000)
        assertFalse(clock.isPaused)
        fakeNow = 150
        assertEquals(5_050, clock.mediaNanos())
    }

    @Test
    fun `seek while paused stays paused at the target`() {
        clock.start()
        clock.pause()
        clock.seek(2_000)
        fakeNow = 700
        assertTrue(clock.isPaused)
        assertEquals(2_000, clock.mediaNanos())
    }

    @Test
    fun `start at an offset anchors media time there`() {
        clock.start(atMediaNanos = 10_000)
        fakeNow = 250
        assertEquals(10_250, clock.mediaNanos())
    }

    @Test
    fun `nanosUntilDue is positive before and negative after the pts`() {
        clock.start()
        fakeNow = 400
        assertEquals(100, clock.nanosUntilDue(500))
        assertEquals(-150, clock.nanosUntilDue(250))
    }

    @Test
    fun `rate scales wall deltas`() {
        clock.start()
        clock.setRate(2.0)
        fakeNow = 500
        assertEquals(1_000, clock.mediaNanos())
    }

    @Test
    fun `a rate change re-anchors -- history keeps its scale`() {
        clock.start()
        fakeNow = 400
        assertEquals(400, clock.mediaNanos())
        clock.setRate(0.5)
        assertEquals(400, clock.mediaNanos(), "continuous at the change")
        fakeNow = 1_000
        assertEquals(700, clock.mediaNanos(), "the past at 1x, the future at 0.5x")
    }

    @Test
    fun `rate survives pause and resume`() {
        clock.start()
        clock.setRate(2.0)
        fakeNow = 100
        clock.pause()
        fakeNow = 600
        assertEquals(200, clock.mediaNanos())
        clock.resume()
        fakeNow = 850
        assertEquals(700, clock.mediaNanos())
    }
}
