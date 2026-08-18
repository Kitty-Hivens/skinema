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
        // encoder, decoder, container. The three differ: the CLI encodes what
        // it was built with, the bundle decodes a narrower list of its own,
        // and the container needs a demuxer that is a third list again --
        // .wv has no demuxer in any tier, so wavpack rides matroska.
        val cases = listOf(
            Triple("flac", "flac", "mka"),
            Triple("aac", "aac", "m4a"),
            Triple("libopus", "opus", "opus"),
            Triple("libmp3lame", "mp3", "mp3"),
            Triple("libvorbis", "vorbis", "ogg"),
            Triple("ac3", "ac3", "ac3"),
            Triple("eac3", "eac3", "eac3"),
            Triple("alac", "alac", "m4a"),
            Triple("pcm_s16le", "pcm_s16le", "wav"),
            Triple("pcm_s24le", "pcm_s24le", "wav"),
            Triple("pcm_f32le", "pcm_f32le", "wav"),
            Triple("wavpack", "wavpack", "mka"),
        )
        val ran = mutableListOf<String>()
        for ((codec, decoderName, ext) in cases) {
            if (!Fixtures.hasCliEncoder(codec)) continue
            if (!Fixtures.libraryHasDecoder(decoderName)) continue
            val out = dir.resolve("a-$codec.$ext")
            val built = runCatching {
                Fixtures.generate(
                    out,
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$rate", "-t", "1", "-c:a", codec,
                )
            }
            if (built.isFailure) continue
            ran += codec

            runCatching { checkCodec(codec, out, rate) }
                .onFailure { throw AssertionError("$codec: ${it.message}", it) }
        }
        assertTrue(ran.size >= 4, "too few audio codecs present to mean anything, ran $ran")
    }

    private fun checkCodec(codec: String, out: Path, rate: Int) {
        run {
            val decoder = assertNotNull(AudioDecoder.openOrNull(out), "produced nothing openable")
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
                assertEquals(rate, seenRate, "came back at the wrong sample rate")
                // A second, give or take the codec's own priming and padding:
                // vorbis trims, ac3 pads to its frame.
                assertTrue(
                    abs(frames - rate) < rate / 10,
                    "decoded $frames frames for one second at $rate",
                )
                val rms = sqrt(energy / (frames * 2))
                assertTrue(rms > 100, "decoded silence (rms $rms) where a sine was written")
            }
        }
    }
}
