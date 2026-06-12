package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `the container's duration surfaces for audio files`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(tone("dur.flac", "-c:a", "flac"))!!.use { decoder ->
            val d = assertNotNull(decoder.durationNanos, "flac declares its duration")
            assertTrue(d in 900_000_000L..1_300_000_000L, "a 1s tone, got ${d}ns")
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

    private fun twoTracks(name: String = "tracks.mka"): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
        "-map", "0:a", "-map", "1:a", "-t", "1", "-c:a", "flac",
        "-metadata:s:a:0", "language=jpn",
        "-metadata:s:a:1", "language=rus", "-metadata:s:a:1", "title=Commentary",
        "-disposition:a:0", "default",
    )

    @Test
    fun `the container's audio tracks enumerate with their metadata`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(twoTracks())!!.use { decoder ->
            val tracks = decoder.tracks
            assertEquals(2, tracks.size, "both streams are audio")
            val jpn = tracks[0]
            assertEquals("jpn", jpn.language)
            assertTrue(jpn.isDefault, "the explicit default disposition must surface")
            assertEquals(44_100, jpn.sampleRate)
            assertEquals(1, jpn.channels, "lavfi sine is mono")
            val rus = tracks[1]
            assertEquals("rus", rus.language)
            assertEquals("Commentary", rus.title)
            assertTrue(!rus.isDefault)
            assertEquals(48_000, rus.sampleRate)
        }
    }

    @Test
    fun `the default disposition wins the best-stream pick`() {
        // Sound on the pinned n8.1: av_find_best_stream ranks
        // AV_DISPOSITION_DEFAULT as the leading criterion since FFmpeg 5.0;
        // without it the tiebreak could pick either sine.
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(twoTracks("def.mka"))!!.use { decoder ->
            assertEquals(0, decoder.streamIndex)
            assertEquals(44_100, decoder.nextChunk()!!.sampleRate)
        }
    }

    @Test
    fun `an explicit stream index opens that track`() {
        Fixtures.assumeDecodeEnvironment()
        AudioDecoder.openOrNull(twoTracks("pick.mka"), streamIndex = 1)!!.use { decoder ->
            assertEquals(1, decoder.streamIndex)
            assertEquals(48_000, decoder.nextChunk()!!.sampleRate)
        }
    }

    @Test
    fun `a non-audio or out-of-range index fails loudly`() {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("av.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "aac", "-shortest",
        )
        assertFailsWith<LibavException>("the video stream is not an audio track") {
            AudioDecoder.openOrNull(av, streamIndex = 0)
        }
        assertFailsWith<LibavException>("out of range") {
            AudioDecoder.openOrNull(av, streamIndex = 99)
        }
    }

    @Test
    fun `cover art ships as the stored bytes`() {
        Fixtures.assumeDecodeEnvironment()
        val png = Fixtures.generate(
            dir.resolve("cover.png"),
            "-f", "lavfi", "-i", "color=c=red:size=16x16", "-frames:v", "1",
        )
        val flac = Fixtures.generate(
            dir.resolve("covered.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-i", png.toString(),
            "-map", "0:a", "-map", "1:v", "-t", "1",
            "-c:a", "flac", "-c:v", "png", "-disposition:v:0", "attached_pic",
        )
        AudioDecoder.openOrNull(flac)!!.use { decoder ->
            val art = assertNotNull(decoder.coverArt, "the flac picture block must surface")
            assertTrue(art.size > 8, "the encoded image travels whole")
            assertEquals(0x89.toByte(), art[0], "png bytes ship exactly as stored")
            assertEquals('P'.code.toByte(), art[1])
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
