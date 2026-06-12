package dev.hivens.skinema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioClockTest {

    private var frames = 0L
    private val clock = AudioClock(48_000) { frames }

    @Test
    fun `media time is consumed samples over the rate`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        frames = 60_000
        assertEquals(1_250_000_000L, clock.mediaNanos())
    }

    @Test
    fun `starts anchored at the given media position`() {
        frames = 1_000
        clock.start(5_000_000_000L)
        frames = 1_000 + 24_000
        assertEquals(5_500_000_000L, clock.mediaNanos())
    }

    @Test
    fun `a frozen position freezes media time -- pause by construction`() {
        clock.start(0)
        frames = 9_600
        val before = clock.mediaNanos()
        clock.pause()
        assertEquals(before, clock.mediaNanos(), "a stopped device stops time")
        assertTrue(clock.isPaused)
    }

    @Test
    fun `seek re-anchors at the current device position`() {
        clock.start(0)
        frames = 10_000
        clock.seek(2_000_000_000L)
        assertEquals(2_000_000_000L, clock.mediaNanos())
        frames = 10_000 + 4_800
        assertEquals(2_100_000_000L, clock.mediaNanos())
    }

    @Test
    fun `a backward device-position wobble does not run media time backward`() {
        clock.start(0)
        frames = 9_600
        assertEquals(200_000_000L, clock.mediaNanos())
        // Driver reconciliation glitch: the reported position dips.
        frames = 9_000
        assertEquals(200_000_000L, clock.mediaNanos(), "the wobble must be clamped, not shown")
        frames = 10_000
        assertEquals(208_333_333L, clock.mediaNanos(), "real progress resumes past the clamp")
    }

    @Test
    fun `a backward seek legitimately moves media time backward`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        clock.seek(250_000_000L)
        assertEquals(250_000_000L, clock.mediaNanos(), "a re-anchor resets the monotonic floor")
    }

    @Test
    fun `rebase continues media time at the anchor and scales by the new rate`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        // A track switch reopens the line: position restarts at zero and
        // the rate may change.
        frames = 0
        clock.rebase(1_000_000_000L, 96_000)
        assertEquals(1_000_000_000L, clock.mediaNanos(), "continuous at the anchor")
        frames = 96_000
        assertEquals(2_000_000_000L, clock.mediaNanos(), "deltas scale by the new rate")
    }

    @Test
    fun `detachToWallTime keeps time moving without the device`() {
        clock.start(0)
        frames = 48_000
        clock.detachToWallTime()
        val atDetach = clock.mediaNanos()
        assertTrue(atDetach >= 1_000_000_000L)
        Thread.sleep(50)
        assertTrue(
            clock.mediaNanos() >= atDetach + 30_000_000L,
            "detached time must advance on the wall clock even with the device frozen",
        )
    }
}
