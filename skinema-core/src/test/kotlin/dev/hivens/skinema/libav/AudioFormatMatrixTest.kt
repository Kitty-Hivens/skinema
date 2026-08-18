package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The audio decode path across the codecs it claims, rather than the two it
 * was written against: flac supplied 28 of the suite's audio fixtures and
 * aac 10, with one each of a few PCM flavours and nothing else. Everything
 * the resampler does -- planar to interleaved, every sample format, every
 * layout -- ran on those two.
 *
 * A sine is the probe because silence and garbage both fail it: the decode
 * has to come back at the right rate, the right length, and carrying signal.
 */
class AudioFormatMatrixTest {

    private val dir: Path = Files.createTempDirectory("skinema-audio-matrix")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a sine survives every audio codec`() {
        Fixtures.assumeDecodeEnvironment()
        val rate = 48_000
        val cases = listOf(
            "flac" to "mka", "aac" to "m4a", "libopus" to "opus", "libmp3lame" to "mp3",
            "libvorbis" to "ogg", "ac3" to "ac3", "eac3" to "eac3", "alac" to "m4a",
            "pcm_s16le" to "wav", "pcm_s24le" to "wav", "pcm_f32le" to "wav", "wavpack" to "wv",
        )
        val ran = mutableListOf<String>()
        for ((codec, ext) in cases) {
            if (!Fixtures.hasCliEncoder(codec)) continue
            val out = dir.resolve("a-$codec.$ext")
            val built = runCatching {
                Fixtures.generate(
                    out,
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$rate", "-t", "1", "-c:a", codec,
                )
            }
            if (built.isFailure) continue
            ran += codec

            val decoder = assertNotNull(AudioDecoder.openOrNull(out), "$codec produced nothing openable")
            decoder.use { d ->
                var frames = 0L
                var energy = 0.0
                var seenRate = 0
                while (true) {
                    val chunk = d.nextChunk() ?: break
                    seenRate = chunk.sampleRate
                    // S16LE interleaved stereo throughout, by contract.
                    for (i in 0 until chunk.byteCount / 2) {
                        val lo = chunk.pcm[i * 2].toInt() and 0xFF
                        val hi = chunk.pcm[i * 2 + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort().toDouble()
                        energy += sample * sample
                    }
                    frames += chunk.byteCount / 4
                }
                assertEquals(rate, seenRate, "$codec came back at the wrong sample rate")
                // A second, give or take the codec's own priming and padding:
                // vorbis trims, ac3 pads to its frame.
                assertTrue(
                    abs(frames - rate) < rate / 10,
                    "$codec decoded $frames frames for one second at $rate",
                )
                val rms = sqrt(energy / (frames * 2))
                assertTrue(rms > 100, "$codec decoded silence (rms $rms) where a sine was written")
            }
        }
        assertTrue(ran.size >= 4, "too few audio codecs present to mean anything, ran $ran")
    }
}
