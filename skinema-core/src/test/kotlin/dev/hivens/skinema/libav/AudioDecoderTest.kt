package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioDecoderTest {

    private val dir: Path = Files.createTempDirectory("skinema-audio-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun tone(name: String, vararg extra: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", *extra,
    )

    @Test
    fun `flac decodes losslessly to the exact sample count`() {
        Fixtures.assumeDecodeEnvironment()
        val decoder = assertNotNull(AudioDecoder.openOrNull(tone("tone.flac", "-c:a", "flac")))
        decoder.use {
            var samples = 0L
            var chunks = 0
            while (true) {
                val chunk = it.nextChunk() ?: break
                assertEquals(44_100, chunk.sampleRate)
                assertTrue(chunk.byteCount % 4 == 0, "S16 stereo frames are 4 bytes")
                samples += chunk.byteCount / 4
                chunks++
            }
            assertEquals(44_100L, samples, "1s of lossless 44.1kHz must decode to exactly 44100 frames")
            assertTrue(chunks > 1, "the second of audio should arrive in several chunks")
        }
    }

    @Test
    fun `chunk pts follows the cumulative sample grid`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(tone("grid.flac", "-c:a", "flac"))!!.use { decoder ->
            var cumulative = 0L
            while (true) {
                val chunk = decoder.nextChunk() ?: break
                assertEquals(
                    cumulative * 1_000_000_000L / 44_100,
                    chunk.ptsNanos,
                    "chunk pts must equal the samples already played",
                )
                cumulative += chunk.byteCount / 4
            }
        }
    }

    @Test
    fun `aac decodes to roughly a second -- priming and padding included`() {
        Fixtures.assumeDecodeEnvironment()
        val decoder = assertNotNull(AudioDecoder.openOrNull(tone("tone.m4a", "-c:a", "aac")))
        decoder.use {
            var samples = 0L
            while (true) {
                val chunk = it.nextChunk() ?: break
                samples += chunk.byteCount / 4
            }
            assertTrue(samples in 42_000..48_000, "aac adds priming/padding, got $samples frames")
        }
    }

    @Test
    fun `ac3 decodes and a 5_1 layout downmixes to stereo`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("ac3")
        val decoder = assertNotNull(
            AudioDecoder.openOrNull(tone("tone.ac3", "-ac", "6", "-c:a", "ac3")),
            "a raw ac3 stream must open",
        )
        decoder.use {
            var samples = 0L
            while (true) {
                val chunk = it.nextChunk() ?: break
                assertEquals(44_100, chunk.sampleRate)
                assertTrue(chunk.byteCount % 4 == 0, "the 5.1 source must arrive as S16 stereo")
                samples += chunk.byteCount / 4
            }
            assertTrue(samples in 42_000..48_000, "ac3 pads to 1536-sample frames, got $samples")
        }
    }

    @Test
    fun `eac3 decodes`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("eac3")
        AudioDecoder.openOrNull(tone("tone.eac3", "-c:a", "eac3"))!!.use { decoder ->
            var samples = 0L
            while (true) {
                val chunk = decoder.nextChunk() ?: break
                samples += chunk.byteCount / 4
            }
            assertTrue(samples in 42_000..48_000, "eac3 pads to 1536-sample frames, got $samples")
        }
    }

    @Test
    fun `alac in m4a decodes losslessly to the exact sample count`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("alac")
        AudioDecoder.openOrNull(tone("alac.m4a", "-c:a", "alac"))!!.use { decoder ->
            var samples = 0L
            while (true) {
                val chunk = decoder.nextChunk() ?: break
                samples += chunk.byteCount / 4
            }
            assertEquals(44_100L, samples, "alac is lossless; the exact count must survive")
        }
    }

    @Test
    fun `24-bit wav decodes sample-exact`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(tone("tone24.wav", "-c:a", "pcm_s24le"))!!.use { decoder ->
            var samples = 0L
            while (true) {
                val chunk = decoder.nextChunk() ?: break
                samples += chunk.byteCount / 4
            }
            assertEquals(44_100L, samples, "pcm has no framing; the count is exact")
        }
    }

    @Test
    fun `float wav decodes sample-exact`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(tone("tonef32.wav", "-c:a", "pcm_f32le"))!!.use { decoder ->
            var samples = 0L
            while (true) {
                val chunk = decoder.nextChunk() ?: break
                samples += chunk.byteCount / 4
            }
            assertEquals(44_100L, samples, "pcm has no framing; the count is exact")
        }
    }

    @Test
    fun `seek then decode resumes at-or-before the target`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(tone("seek.flac", "-c:a", "flac"))!!.use { decoder ->
            repeat(3) { decoder.nextChunk() }
            val target = 500_000_000L
            decoder.seekTo(target)
            val chunk = assertNotNull(decoder.nextChunk(), "seek must land inside the stream")
            assertTrue(chunk.ptsNanos <= target, "BACKWARD seek lands at-or-before, got ${chunk.ptsNanos}")
        }
    }

    @Test
    fun `a silent video has no audio stream -- openOrNull says so`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("silent.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-an",
        )
        assertNull(AudioDecoder.openOrNull(video))
    }
}
