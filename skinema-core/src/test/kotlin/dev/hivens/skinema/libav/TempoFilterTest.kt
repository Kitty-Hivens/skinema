package dev.hivens.skinema.libav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TempoFilterTest {

    /** One second of 440 Hz sine, S16LE stereo at 48 kHz. */
    private fun sineSecond(rate: Int = 48_000): ByteArray {
        val pcm = ByteArray(rate * 4)
        for (i in 0 until rate) {
            val v = (sin(2 * PI * 440 * i / rate) * 12_000).toInt()
            pcm[i * 4] = (v and 0xFF).toByte()
            pcm[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
            pcm[i * 4 + 2] = pcm[i * 4]
            pcm[i * 4 + 3] = pcm[i * 4 + 1]
        }
        return pcm
    }

    /** Feeds one second in decoder-sized chunks; returns output frames. */
    private fun stretchedFrames(tempo: Double): Int {
        var bytes = 0
        TempoFilter(48_000, tempo).use { f ->
            val pcm = sineSecond()
            var off = 0
            while (off < pcm.size) {
                val len = minOf(4096 * 4, pcm.size - off)
                bytes += f.process(pcm.copyOfRange(off, off + len), len)
                off += len
            }
            bytes += f.flush()
        }
        return bytes / 4
    }

    @Test
    fun `tempo 2 halves the sample count`() {
        Fixtures.assumeDecodeEnvironment()
        val frames = stretchedFrames(2.0)
        assertTrue(abs(frames - 24_000) <= 1_200, "1s at tempo 2 should yield ~0.5s, got $frames frames")
    }

    @Test
    fun `tempo half doubles the sample count`() {
        Fixtures.assumeDecodeEnvironment()
        val frames = stretchedFrames(0.5)
        assertTrue(abs(frames - 96_000) <= 4_800, "1s at tempo 0.5 should yield ~2s, got $frames frames")
    }

    @Test
    fun `reset drops the buffered state`() {
        Fixtures.assumeDecodeEnvironment()
        TempoFilter(48_000, 2.0).use { f ->
            val pcm = sineSecond()
            f.process(pcm, pcm.size)
            f.reset()
            assertEquals(0, f.flush(), "a fresh graph has nothing to drain")
        }
    }

    @Test
    fun `reset revives a drained graph`() {
        Fixtures.assumeDecodeEnvironment()
        TempoFilter(48_000, 1.5).use { f ->
            val pcm = sineSecond()
            f.process(pcm, pcm.size)
            f.flush()
            f.reset()
            val out = f.process(pcm, pcm.size) + f.flush()
            assertTrue(out > 0, "after flush the graph is spent; reset must revive it")
        }
    }
}
