package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoDecoderTest {

    /**
     * Whether a subtitle codec is text is asked of the library, not of a
     * list. The list was five ids long, and every text codec outside it --
     * sami, microdvd, TTML, plain text -- arrived flagged as a bitmap, so
     * the bitmap branch skipped the ASS rects their decoders emit: the track
     * selected, reported itself active, ran a thread and drew nothing, with
     * no error anywhere.
     *
     * Asked of the descriptor table rather than of a file, and deliberately.
     * No bundle carries a decoder for any of those formats -- the whitelist
     * is exactly the five the old list named plus the two bitmap ones -- so a
     * fixture could not reach the case on the binaries this ships. The
     * descriptor table is compiled in whether or not a decoder is, which is
     * what makes the question answerable at all, and answering it correctly
     * is the whole of the fix.
     */
    @Test
    fun `a text subtitle codec is recognised whether or not it was on the old list`() {
        Fixtures.assumeDecodeEnvironment()
        // Found by name off the library's own table, so the id is never
        // hard-coded here: the oracle carries the handful of ids the code
        // needs and these are not among them.
        fun idOf(name: String): Int? =
            (LibavAbi.AV_CODEC_ID_DVD_SUBTITLE until LibavAbi.AV_CODEC_ID_DVD_SUBTITLE + 64)
                .firstOrNull { Libav.avcodecGetName(it).reinterpret(Long.MAX_VALUE).getString(0) == name }

        for (name in listOf("sami", "microdvd", "text", "ttml", "jacosub", "realtext")) {
            val id = idOf(name) ?: continue
            assertTrue(isTextSubtitleCodec(id), "$name is a text subtitle codec")
        }
        for (name in listOf("ass", "ssa", "subrip", "mov_text", "webvtt")) {
            val id = assertNotNull(idOf(name), "the library must name $name")
            assertTrue(isTextSubtitleCodec(id), "$name is a text subtitle codec")
        }
        // The other half: a bitmap codec must not be dragged in with them.
        for (name in listOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")) {
            val id = idOf(name) ?: continue
            assertFalse(isTextSubtitleCodec(id), "$name draws pixels, not text")
        }
    }

    private val dir: Path = Files.createTempDirectory("skinema-decoder-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /**
     * AutoCloseable asks for idempotence and this did not give it: the scaler
     * contexts are freed before the arena is touched and were not cleared, so
     * a second close freed them again. That is a double free, which aborts the
     * process rather than throwing -- a regression here does not fail this
     * test, it takes the whole test JVM down, which is the correct amount of
     * noise for memory corruption in a public class.
     */
    @Test
    fun `closing a decoder twice is safe`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("twice.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val decoder = VideoDecoder.open(video)
        // A frame first: the scaler contexts only exist once something was
        // converted, and they are what the second close freed twice.
        assertNotNull(decoder.nextFrame(), "the fixture must decode")
        decoder.close()
        decoder.close()
    }

    @Test
    fun `decodes every frame with pts on the frame grid`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("grid.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video).use { decoder ->
            val pts = generateSequence { decoder.nextFrame()?.ptsNanos }.toList()
            assertEquals(List(10) { it * 100_000_000L }, pts)
        }
    }

    @Test
    fun `nonzero start_time normalizes the timeline to zero`() {
        Fixtures.assumeDecodeEnvironment()
        // Matroska carries a nonzero container start_time via
        // -output_ts_offset and is in the trimmed-build demuxer whitelist
        // (mpegts is not). Its pts and start_time stay consistent, so the
        // exact offset does not matter -- normalization brings the first
        // frame to 0 and the grid back to a clean 100ms step.
        val video = Fixtures.generate(
            dir.resolve("offset.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-output_ts_offset", "1.4",
        )
        VideoDecoder.open(video).use { decoder ->
            val pts = generateSequence { decoder.nextFrame()?.ptsNanos }.take(20).toList()
            assertEquals(List(20) { it * 100_000_000L }, pts, "the offset timeline must normalize to a zero grid")
        }
    }

    @Test
    fun `seek lands at the normalized target on a nonzero start_time stream`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("offset-seek.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "3",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "5",
            "-output_ts_offset", "1.4",
        )
        val target = 1_000_000_000L
        VideoDecoder.open(video).use { decoder ->
            decoder.seekTo(target)
            var frame = decoder.nextFrame()!!
            assertTrue(frame.ptsNanos <= target, "BACKWARD seek lands at-or-before the normalized target, got ${frame.ptsNanos}")
            while (frame.ptsNanos < target) frame = decoder.nextFrame()!!
            assertEquals(target, frame.ptsNanos, "decode-forward hits the normalized target exactly")
        }
    }

    @Test
    fun `solid red decodes to red pixels`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("red.mp4"),
            "-f", "lavfi", "-i", "color=c=red:size=16x16:rate=5", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video).use { decoder ->
            val frame = decoder.nextFrame()!!
            assertEquals(16, frame.width)
            assertEquals(16, frame.height)
            // Center pixel; generous tolerances absorb yuv420p rounding.
            val i = (8 * 16 + 8) * 4
            val r = frame.rgba[i].toInt() and 0xFF
            val g = frame.rgba[i + 1].toInt() and 0xFF
            val b = frame.rgba[i + 2].toInt() and 0xFF
            val a = frame.rgba[i + 3].toInt() and 0xFF
            assertTrue(r > 200, "red channel should dominate, got $r")
            assertTrue(g < 50 && b < 50, "green/blue should be near zero, got g=$g b=$b")
            assertEquals(255, a, "opaque input must stay opaque")
        }
    }

    @Test
    fun `seek then decode-forward reaches the exact target frame`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("seek.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "3",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "5",
        )
        val target = 1_500_000_000L
        VideoDecoder.open(video).use { decoder ->
            repeat(3) { decoder.nextFrame() }
            decoder.seekTo(target)
            var frame = decoder.nextFrame()!!
            assertTrue(frame.ptsNanos <= target, "BACKWARD seek must land at-or-before the target, got ${frame.ptsNanos}")
            while (frame.ptsNanos < target) frame = decoder.nextFrame()!!
            assertEquals(target, frame.ptsNanos, "decode-forward must hit the requested frame exactly")
        }
    }

    @Test
    fun `after EOF a seek to zero decodes again -- the loop primitive`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("loop.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "0.5",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video).use { decoder ->
            val drained = generateSequence { decoder.nextFrame() }.count()
            assertTrue(drained > 0, "the fixture should decode at least one frame")
            assertNull(decoder.nextFrame(), "stream is drained")
            decoder.seekTo(0)
            assertEquals(0L, decoder.nextFrame()?.ptsNanos, "seek must reopen a drained stream")
        }
    }

    @Test
    fun `hevc decodes -- the whitelist carries it`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libx265")
        val video = Fixtures.generate(
            dir.resolve("hevc.mp4"),
            // lime, not green: lavfi color names are HTML's, where green
            // means half-intensity #008000.
            "-f", "lavfi", "-i", "color=c=lime:size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx265", "-preset", "ultrafast", "-crf", "28",
        )
        VideoDecoder.open(video).use { decoder ->
            val frames = generateSequence { decoder.nextFrame()?.let { it.ptsNanos to it.rgba[(32 * 64 + 32) * 4 + 1] } }.toList()
            assertEquals(10, frames.size)
            val g = frames.first().second.toInt() and 0xFF
            assertTrue(g > 200, "green channel should dominate, got $g")
        }
    }

    @Test
    fun `vp9 with alpha preserves transparency`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx-vp9")
        val video = Fixtures.generate(
            dir.resolve("alpha.webm"),
            "-f", "lavfi", "-i", "color=c=red@0.5:size=16x16:rate=5,format=yuva420p", "-t", "1",
            "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-deadline", "realtime", "-cpu-used", "8",
        )
        VideoDecoder.open(video).use { decoder ->
            val frame = decoder.nextFrame()!!
            val i = (8 * 16 + 8) * 4
            val r = frame.rgba[i].toInt() and 0xFF
            val a = frame.rgba[i + 3].toInt() and 0xFF
            assertTrue(r > 100, "premultiplied-or-not, red should be visibly present, got $r")
            assertTrue(a in 96..160, "alpha 0.5 should survive the pipeline, got $a")
        }
    }

    /** Decodes [name] and asserts frame count, dimensions and a monotonic pts grid. */
    private fun assertDecodesEveryFrame(name: String, expectedFrames: Int, vararg encodeArgs: String) =
        assertDecodesEveryFrame(Fixtures.generate(dir.resolve(name), *encodeArgs), expectedFrames)

    private fun assertDecodesEveryFrame(video: Path, expectedFrames: Int) {
        val name = video.fileName.toString()
        VideoDecoder.open(video).use { decoder ->
            var count = 0
            var lastPts = Long.MIN_VALUE
            while (true) {
                val frame = decoder.nextFrame() ?: break
                count++
                assertEquals(64, frame.width, "$name width")
                assertEquals(64, frame.height, "$name height")
                assertTrue(frame.ptsNanos > lastPts, "$name pts must be monotonic, got ${frame.ptsNanos} after $lastPts")
                lastPts = frame.ptsNanos
            }
            assertEquals(expectedFrames, count, "$name frame count")
        }
    }

    @Test
    fun `vp8 in webm decodes -- the whitelist carries it`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx")
        assertDecodesEveryFrame(
            "vp8.webm", 10,
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8",
        )
    }

    @Test
    fun `av1 decodes through dav1d -- the whitelist carries it`() {
        Fixtures.assumeDecodeEnvironment()
        // Not gated on the CLI's own encoder any more: dav1d ships in every
        // tier, and on the one platform whose CLI cannot encode AV1 this test
        // had never run at all. The fixture takes whichever route exists.
        Fixtures.assumeAv1Fixture()
        assertDecodesEveryFrame(Fixtures.av1(dir.resolve("av1.mp4")), 10)
    }

    @Test
    fun `animated gif rides the same pipeline -- the consumer's animated category`() {
        Fixtures.assumeDecodeEnvironment()
        assertDecodesEveryFrame(
            "anim.gif", 10,
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
        )
    }

    @Test
    fun `apng rides the same pipeline and keeps alpha`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("anim.apng"),
            "-f", "lavfi", "-i", "color=c=red@0.5:size=16x16:rate=5,format=rgba", "-t", "1",
            "-f", "apng",
        )
        VideoDecoder.open(video).use { decoder ->
            val frame = decoder.nextFrame()!!
            val i = (8 * 16 + 8) * 4
            val a = frame.rgba[i + 3].toInt() and 0xFF
            assertTrue(a in 96..160, "apng alpha 0.5 should survive, got $a")
        }
    }

    @Test
    fun `still webp decodes as a single frame through the libav path`() {
        // Stills go through ffmpeg's own webp decoder; the animated ones
        // through webp_anim, covered by WebpDecodingTest.
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libwebp")
        assertDecodesEveryFrame(
            "still.webp", 1,
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-frames:v", "1",
            "-c:v", "libwebp",
        )
    }

    @Test
    fun `mjpeg in mov decodes -- the whitelist carries it`() {
        Fixtures.assumeDecodeEnvironment()
        assertDecodesEveryFrame(
            "mjpeg.mov", 10,
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuvj420p", "-c:v", "mjpeg", "-q:v", "4",
        )
    }

    @Test
    fun `the container's duration surfaces in nanoseconds`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("dur.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video).use { decoder ->
            val d = assertNotNull(decoder.durationNanos(), "mp4 declares its duration")
            assertTrue(d in 900_000_000L..1_300_000_000L, "1s of footage, got ${d}ns")
        }
    }

    @Test
    fun `webm duration surfaces too`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx")
        val video = Fixtures.generate(
            dir.resolve("dur.webm"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8",
        )
        VideoDecoder.open(video).use { decoder ->
            val d = assertNotNull(decoder.durationNanos(), "webm declares its duration")
            assertTrue(d in 900_000_000L..1_300_000_000L, "1s of footage, got ${d}ns")
        }
    }

    @Test
    fun `chapters and tags surface from the container`() {
        Fixtures.assumeDecodeEnvironment()
        val meta = dir.resolve("meta.txt")
        Files.writeString(
            meta,
            """
            ;FFMETADATA1
            title=Album Mix
            artist=Composer
            [CHAPTER]
            TIMEBASE=1/1000
            START=0
            END=500
            title=Intro
            [CHAPTER]
            TIMEBASE=1/1000
            START=500
            END=1000
            title=Drop
            """.trimIndent(),
        )
        val video = Fixtures.generate(
            dir.resolve("chapters.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-i", meta.toString(), "-map_metadata", "1", "-map", "0:v", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video).use { decoder ->
            assertEquals("Album Mix", decoder.tags()["title"])
            assertEquals("Composer", decoder.tags()["artist"])
            val chapters = decoder.chapters()
            assertEquals(2, chapters.size)
            assertEquals("Intro", chapters[0].title)
            assertEquals(0L, chapters[0].startNanos)
            assertEquals(500_000_000L, chapters[0].endNanos)
            assertEquals("Drop", chapters[1].title)
            assertEquals(1_000_000_000L, chapters[1].endNanos)
        }
    }

    @Test
    fun `unspecified matrix takes the geometry convention`() {
        assertEquals(LibavAbi.SWS_CS_ITU709, swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, 1920, 1080))
        assertEquals(LibavAbi.SWS_CS_ITU709, swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, 1280, 720))
        assertEquals(LibavAbi.SWS_CS_ITU601, swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, 640, 480))
    }

    @Test
    fun `a declared matrix wins over geometry`() {
        assertEquals(LibavAbi.SWS_CS_ITU601, swsCoefficientsFor(LibavAbi.AVCOL_SPC_SMPTE170M, 1920, 1080))
        assertEquals(LibavAbi.SWS_CS_ITU601, swsCoefficientsFor(LibavAbi.AVCOL_SPC_BT470BG, 1920, 1080))
        assertEquals(LibavAbi.SWS_CS_ITU709, swsCoefficientsFor(LibavAbi.AVCOL_SPC_BT709, 320, 240))
        assertEquals(LibavAbi.SWS_CS_BT2020, swsCoefficientsFor(LibavAbi.AVCOL_SPC_BT2020_NCL, 3840, 2160))
    }

    private fun centerRgb(frame: VideoDecoder.RgbaFrame): Triple<Int, Int, Int> {
        val i = (frame.height / 2 * frame.width + frame.width / 2) * 4
        return Triple(
            frame.rgba[i].toInt() and 0xFF,
            frame.rgba[i + 1].toInt() and 0xFF,
            frame.rgba[i + 2].toInt() and 0xFF,
        )
    }

    private fun assertRgbNear(expected: Triple<Int, Int, Int>, actual: Triple<Int, Int, Int>, what: String) {
        val (er, eg, eb) = expected
        val (r, g, b) = actual
        assertTrue(
            abs(r - er) <= 8 && abs(g - eg) <= 8 && abs(b - eb) <= 8,
            "$what: authored ($er,$eg,$eb) must survive the round-trip, got ($r,$g,$b)",
        )
    }

    @Test
    fun `bt709-tagged color decodes through the 709 matrix`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("bt709.mp4"),
            "-f", "lavfi", "-i", "color=c=0x28B428:size=64x64:rate=5", "-t", "1",
            "-vf", "scale=out_color_matrix=bt709,format=yuv420p",
            "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
            "-c:v", "libx264", "-qp", "0", "-preset", "ultrafast",
        )
        VideoDecoder.open(video).use { decoder ->
            // Decoded through the 601 default instead, (40,180,40) lands
            // at ~(51,204,45) -- the green channel is the discriminator.
            assertRgbNear(Triple(40, 180, 40), centerRgb(decoder.nextFrame()!!), "bt709")
        }
    }

    @Test
    fun `smpte170m-tagged color decodes through the 601 matrix`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("bt601.mp4"),
            "-f", "lavfi", "-i", "color=c=0x28B428:size=64x64:rate=5", "-t", "1",
            "-vf", "scale=out_color_matrix=smpte170m,format=yuv420p",
            "-colorspace", "smpte170m",
            "-c:v", "libx264", "-qp", "0", "-preset", "ultrafast",
        )
        VideoDecoder.open(video).use { decoder ->
            assertRgbNear(Triple(40, 180, 40), centerRgb(decoder.nextFrame()!!), "smpte170m")
        }
    }

    @Test
    fun `every matrix the mapping names keeps its own coefficients`() {
        // FCC has no pixel test and cannot have one: its coefficients differ
        // from BT.601 by at most 1.52 levels of 255 anywhere in the colour
        // cube, which no 8-bit round trip can separate. The arm still has to
        // survive, so it is held where it is observable -- at the mapping.
        assertEquals(LibavAbi.SWS_CS_FCC, swsCoefficientsFor(LibavAbi.AVCOL_SPC_FCC, 640, 480))
        assertEquals(LibavAbi.SWS_CS_SMPTE240M, swsCoefficientsFor(LibavAbi.AVCOL_SPC_SMPTE240M, 640, 480))
        assertEquals(LibavAbi.SWS_CS_ITU709, swsCoefficientsFor(LibavAbi.AVCOL_SPC_BT709, 320, 240))
        assertEquals(LibavAbi.SWS_CS_ITU601, swsCoefficientsFor(LibavAbi.AVCOL_SPC_SMPTE170M, 1920, 1080))
        assertEquals(LibavAbi.SWS_CS_BT2020, swsCoefficientsFor(LibavAbi.AVCOL_SPC_BT2020_NCL, 640, 480))
        // Nothing declared: the convention players agree on, by geometry.
        assertEquals(LibavAbi.SWS_CS_ITU709, swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, 1280, 720))
        assertEquals(LibavAbi.SWS_CS_ITU601, swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, 720, 576))
    }

    @Test
    fun `smpte240m-tagged color decodes through the 240M matrix`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("smpte240m.mp4"),
            "-f", "lavfi", "-i", "color=c=0x28B428:size=64x64:rate=5", "-t", "1",
            "-vf", "scale=out_color_matrix=smpte240m,format=yuv420p",
            "-colorspace", "smpte240m",
            "-c:v", "libx264", "-qp", "0", "-preset", "ultrafast",
        )
        VideoDecoder.open(video).use { decoder ->
            assertRgbNear(Triple(40, 180, 40), centerRgb(decoder.nextFrame()!!), "smpte240m")
        }
    }

    @Test
    fun `full-range stream keeps its levels`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx-vp9")
        // vp9, not h264: the h264 decoder answers full-range VUI by
        // switching to yuvj420p, which swscale range-handles on its own
        // -- the fixture would pass with the range plumbing broken.
        // Modern decoders shun yuvj; vp9 carries the range only in
        // color_range, which is exactly the seam under test.
        val video = Fixtures.generate(
            dir.resolve("fullrange.webm"),
            "-f", "lavfi", "-i", "color=c=0x141414:size=64x64:rate=5", "-t", "1",
            "-vf", "scale=out_range=full,format=yuv420p",
            "-color_range", "pc",
            "-c:v", "libvpx-vp9", "-lossless", "1", "-deadline", "realtime", "-cpu-used", "8",
        )
        VideoDecoder.open(video).use { decoder ->
            // Read as limited range, full-range Y=20 expands to ~5 --
            // dark detail crushed to near-black.
            assertRgbNear(Triple(20, 20, 20), centerRgb(decoder.nextFrame()!!), "full range")
        }
    }

    @Test
    fun `the display matrix surfaces as clockwise display rotation`() {
        Fixtures.assumeDecodeEnvironment()
        val plain = Fixtures.generate(
            dir.resolve("upright.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(plain).use { decoder ->
            assertEquals(0, decoder.rotationDegrees(), "no matrix, no rotation")
        }
        // -display_rotation writes the matrix on a stream copy; its angle
        // is counterclockwise, our contract is clockwise-to-apply.
        val quarter = Fixtures.generate(
            dir.resolve("rot90.mp4"),
            "-display_rotation", "90", "-i", plain.toString(), "-c", "copy",
        )
        VideoDecoder.open(quarter).use { decoder ->
            assertEquals(270, decoder.rotationDegrees(), "90ccw displays as 270cw")
        }
        val half = Fixtures.generate(
            dir.resolve("rot180.mp4"),
            "-display_rotation", "180", "-i", plain.toString(), "-c", "copy",
        )
        VideoDecoder.open(half).use { decoder ->
            assertEquals(180, decoder.rotationDegrees())
        }
    }

    private fun writeSrt(name: String): Path = dir.resolve(name).also {
        Files.writeString(
            it,
            """
            1
            00:00:00,500 --> 00:00:02,000
            Hello subs

            """.trimIndent(),
        )
    }

    private fun writeAss(name: String): Path = dir.resolve(name).also {
        Files.writeString(
            it,
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 480

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,24,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.50,0:00:02.00,Default,,0,0,0,,Styled subs
            """.trimIndent(),
        )
    }

    @Test
    fun `subtitle streams enumerate with metadata and dispositions`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("subs.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", writeSrt("enum.srt").toString(),
            "-i", writeAss("enum.ass").toString(),
            "-map", "0:v", "-map", "1", "-map", "2", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s:0", "srt", "-c:s:1", "ass",
            "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English",
            "-metadata:s:s:1", "language=jpn",
            "-disposition:s:0", "default", "-disposition:s:1", "forced",
        )
        VideoDecoder.open(video).use { decoder ->
            val tracks = decoder.subtitleTracks()
            assertEquals(2, tracks.size, "both subtitle streams must enumerate")
            val srt = tracks[0]
            assertEquals("subrip", srt.codecName)
            assertEquals("eng", srt.language)
            assertEquals("English", srt.title)
            assertTrue(srt.isText)
            assertTrue(srt.isDefault)
            assertTrue(!srt.isForced)
            assertEquals(srt.streamIndex, srt.id, "embedded ids are stream indices")
            assertNull(srt.externalPath)
            val ass = tracks[1]
            assertEquals("ass", ass.codecName)
            assertEquals("jpn", ass.language)
            assertTrue(ass.isText)
            assertTrue(ass.isForced)
            assertEquals(decoder.videoSize(), 64 to 48, "coded geometry surfaces for the renderer")
        }
    }

    @Test
    fun `mov_text in mp4 enumerates as a text track`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("movtext.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", writeSrt("movtext.srt").toString(),
            "-map", "0:v", "-map", "1", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "mov_text",
        )
        VideoDecoder.open(video).use { decoder ->
            val tracks = decoder.subtitleTracks()
            assertEquals(1, tracks.size)
            assertEquals("mov_text", tracks[0].codecName)
            assertTrue(tracks[0].isText)
        }
    }

    @Test
    fun `attachments and plain files do not leak into subtitle tracks`() {
        Fixtures.assumeDecodeEnvironment()
        val plain = Fixtures.generate(
            dir.resolve("nosubs.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(plain).use { decoder ->
            assertEquals(emptyList(), decoder.subtitleTracks())
        }
        // An attachment stream is a different codec type; the enumeration
        // filter must not read it as a subtitle.
        val attach = dir.resolve("attach.bin")
        Files.write(attach, ByteArray(64) { it.toByte() })
        val withAttachment = Fixtures.generate(
            dir.resolve("attached.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-attach", attach.toString(), "-metadata:s:t:0", "mimetype=application/octet-stream",
        )
        VideoDecoder.open(withAttachment).use { decoder ->
            assertEquals(emptyList(), decoder.subtitleTracks(), "attachments are not subtitle tracks")
        }
    }

    @Test
    fun `garbage input fails closed with LibavException`() {
        Fixtures.assumeDecodeEnvironment()
        val junk = dir.resolve("junk.mp4")
        Files.write(junk, ByteArray(4096) { (it * 31).toByte() })
        assertFailsWith<LibavException> { VideoDecoder.open(junk) }
    }

    // Neutral greys: matrix-invariant, so the bt2020 tagging cannot skew the
    // fixture, and the discriminator is purely the transfer handling.
    private fun pqClip(name: String, hex: String): Path = hdrClip(name, hex, "smpte2084")

    private fun hlgClip(name: String, hex: String): Path = hdrClip(name, hex, "arib-std-b67")

    private fun hdrClip(name: String, hex: String, trc: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "color=c=$hex:size=64x64:rate=5", "-t", "1",
        "-vf", "format=yuv420p10le", "-pix_fmt", "yuv420p10le",
        "-colorspace", "bt2020nc", "-color_primaries", "bt2020", "-color_trc", trc,
        "-c:v", "libx265", "-preset", "ultrafast",
        "-x265-params", "range=limited:colormatrix=bt2020nc:transfer=$trc:colorprim=bt2020:log-level=none",
    )

    private fun centerLuma(path: Path): Int = VideoDecoder.open(path).use { decoder ->
        val (r, g, b) = centerRgb(decoder.nextFrame()!!)
        (2 * r + 5 * g + b) / 8
    }

    @Test
    fun `pq hdr decodes through the tone-mapper to a sane opaque value`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libx265")
        VideoDecoder.open(pqClip("pq.mp4", "0x808080")).use { decoder ->
            val frame = decoder.nextFrame()!!
            val (r, g, b) = centerRgb(frame)
            assertTrue(r in 16..240 && g in 16..240 && b in 16..240, "a mid PQ grey must land mid, got ($r,$g,$b)")
            val a = frame.rgba[(frame.height / 2 * frame.width + frame.width / 2) * 4 + 3].toInt() and 0xFF
            assertEquals(255, a, "HDR output is opaque")
        }
    }

    @Test
    fun `pq tone-mapping restores contrast -- the HDR path is engaged`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libx265")
        // The PQ EOTF pulls shadows down and highlights up. The naive SDR
        // path (codes read as gamma) spans only ~140 between these greys; a
        // span this wide can only come from the tone-mapper actually running.
        val dark = centerLuma(pqClip("pq-dark.mp4", "0x303030"))
        val bright = centerLuma(pqClip("pq-bright.mp4", "0xC0C0C0"))
        assertTrue(bright > dark, "brighter source must stay brighter, got dark=$dark bright=$bright")
        assertTrue(bright - dark > 170, "PQ must restore HDR contrast (naive span is ~140), got dark=$dark bright=$bright")
    }

    @Test
    fun `hlg hdr decodes through the tone-mapper to a sane value`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libx265")
        VideoDecoder.open(hlgClip("hlg.mp4", "0x808080")).use { decoder ->
            val (r, g, b) = centerRgb(decoder.nextFrame()!!)
            assertTrue(r in 8..248 && g in 8..248 && b in 8..248, "an HLG grey must land in range, got ($r,$g,$b)")
        }
    }
}
