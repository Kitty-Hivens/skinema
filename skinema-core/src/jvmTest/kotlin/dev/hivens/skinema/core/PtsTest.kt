package dev.hivens.skinema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PtsTest {

    @Test
    fun `zero pts is zero nanos`() {
        assertEquals(0L, ptsToNanos(0, 1, 15360))
    }

    @Test
    fun `one full second in mp4's common 1-15360 base`() {
        assertEquals(1_000_000_000L, ptsToNanos(15360, 1, 15360))
    }

    @Test
    fun `one 30fps frame step lands on a third of a centisecond`() {
        // 512/15360 s = 1/30 s.
        assertEquals(33_333_333L, ptsToNanos(512, 1, 15360))
    }

    @Test
    fun `non-unit numerator -- the NTSC 1001-30000 base`() {
        assertEquals(33_366_666L, ptsToNanos(1, 1001, 30000))
    }

    @Test
    fun `24 hours at 90kHz does not overflow`() {
        // Naive pts * 1e9 would overflow Long here; the seconds/remainder
        // split must not.
        assertEquals(86_400L * 1_000_000_000L, ptsToNanos(7_776_000_000L, 1, 90000))
    }

    @Test
    fun `negative pts (pre-roll) stays proportional`() {
        assertEquals(-1_000_000_000L, ptsToNanos(-90000, 1, 90000))
    }

    @Test
    fun `zero denominator is rejected`() {
        assertFailsWith<IllegalArgumentException> { ptsToNanos(1, 1, 0) }
    }

    @Test
    fun `nanosToPts inverts whole seconds exactly`() {
        assertEquals(15360, nanosToPts(1_000_000_000L, 1, 15360))
        assertEquals(90000, nanosToPts(1_000_000_000L, 1, 90000))
        assertEquals(0, nanosToPts(0, 1, 1000))
    }

    @Test
    fun `nanosToPts round-trips a ptsToNanos value within one unit`() {
        // ptsToNanos floors sub-nanosecond detail (512/15360 s is a
        // repeating decimal); the half-up rounding must recover the unit.
        for (pts in longArrayOf(1, 7, 512, 513, 15359, 123_456)) {
            assertEquals(pts, nanosToPts(ptsToNanos(pts, 1, 15360), 1, 15360))
        }
        for (pts in longArrayOf(1, 2, 29, 30, 31, 1000)) {
            assertEquals(pts, nanosToPts(ptsToNanos(pts, 1001, 30000), 1001, 30000))
        }
    }

    @Test
    fun `nanosToPts rejects non-positive bases`() {
        assertFailsWith<IllegalArgumentException> { nanosToPts(1, 0, 1000) }
        assertFailsWith<IllegalArgumentException> { nanosToPts(1, 1, 0) }
    }
}
