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
