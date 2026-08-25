package dev.hivens.skinema.libav

import java.lang.foreign.Arena
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
    fun `nonzero start_time normalizes audio chunk pts to zero`() {
        Fixtures.assumeDecodeEnvironment()
        // Matroska carries a nonzero container start_time via
        // -output_ts_offset and is in the demuxer whitelist (mpegts is not);
        // the first chunk must land near zero, not ~1.4s+ in -- the same
        // origin the video side subtracts, so A/V stays aligned.
        val ts = Fixtures.generate(
            dir.resolve("offset.mka"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1",
            "-c:a", "aac", "-output_ts_offset", "1.4",
        )
        AudioDecoder.openOrNull(ts)!!.use { decoder ->
            val first = assertNotNull(decoder.nextChunk(), "the stream must decode").ptsNanos
            assertTrue(first < 100_000_000L, "audio timeline must normalize to zero, got ${first}ns")
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
        // Sound on the pinned n9.0: av_find_best_stream ranks
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

    /**
     * AutoCloseable requires it, the video decoder guarantees it explicitly,
     * and this one threw: close() allocates from the confined arena it then
     * closes, so a second call died inside the allocation. Every call site
     * inside the pipeline wraps close in runCatching, which is why nobody
     * noticed -- a consumer holding the decoder directly would not.
     */
    @Test
    fun `close is idempotent`() {
        Fixtures.assumeDecodeEnvironment()
        val tone = Fixtures.generate(
            dir.resolve("twice.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "flac",
        )
        val decoder = assertNotNull(AudioDecoder.openOrNull(tone))
        decoder.close()
        decoder.close()
    }

    /**
     * The resampling graph is cached, and what the cache is keyed on has to be
     * the whole input format. Rebuilding it per frame would be a fresh
     * swresample context for every chunk of every file -- the cost the cache
     * exists to avoid -- so the cheap direction is worth pinning.
     */
    @Test
    fun `the resampler is built once for a stream whose layout never changes`() {
        Fixtures.assumeDecodeEnvironment()
        assertNotNull(AudioDecoder.openOrNull(tone("stable.flac", "-c:a", "flac"))).use { decoder ->
            var chunks = 0
            while (decoder.nextChunk() != null) chunks++
            assertTrue(chunks > 1, "the stream must arrive in several chunks for this to mean anything")
            assertEquals(1, decoder.swrBuilds, "one layout, one graph")
        }
    }

    /**
     * And the expensive direction: the key is the layout itself, not the
     * number of channels in it. Two layouts can name the same count and put
     * those channels on different speakers -- 5.1 against 5.1(side) -- so a
     * graph kept across that change resamples the wrong channel to the wrong
     * output, with nothing to show for it but sound in the wrong places.
     *
     * Asserted through the comparison the cache now asks rather than through a
     * stream that changes layout mid-file: the layouts that differ at an equal
     * count are the ones no fixture this bundle can mux carries.
     */
    @Test
    fun `a copied channel layout compares equal and a different one does not`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val held = arena.allocate(LibavAbi.ChannelLayout.SIZEOF)
            val incoming = arena.allocate(LibavAbi.ChannelLayout.SIZEOF)
            Libav.avChannelLayoutDefault(incoming, 6)
            // A zero-filled layout is AV_CHANNEL_ORDER_UNSPEC over no channels
            // -- the state the decoder starts in, which must match nothing.
            assertTrue(Libav.avChannelLayoutCompare(held, incoming) != 0, "an unset layout matches nothing")
            assertEquals(0, Libav.avChannelLayoutCopy(held, incoming), "the copy must succeed")
            assertEquals(0, Libav.avChannelLayoutCompare(held, incoming), "a copy compares equal to its source")
            Libav.avChannelLayoutDefault(incoming, 2)
            assertTrue(Libav.avChannelLayoutCompare(held, incoming) != 0, "5.1 is not stereo")
            Libav.avChannelLayoutUninit(held)
        }
    }
}
