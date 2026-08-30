package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The join between decode and encode: read a file, write another. Asserts
 * on meaning -- the picture comes back the right size and length, the sound
 * survives, the orientation is applied -- not on bytes, which a re-render
 * cannot promise.
 */
class TranscoderTest {

    private val dir: Path = Files.createTempDirectory("skinema-transcode-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun source(name: String, seconds: String = "1", extra: List<String> = emptyList()): Path =
        Fixtures.generate(
            dir.resolve(name),
            *(
                listOf(
                    "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", seconds,
                    "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
                ) + extra
                ).toTypedArray(),
        )

    @Test
    fun `a file transcodes into one the decoder reads back`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("libx264")
        val input = source("plain.mp4")
        val out = dir.resolve("plain-out.mp4")
        Transcoder.open(input, out, TranscodeConfig(videoCodec = "libx264")).use { it.run() }

        VideoDecoder.open(out).use { d ->
            assertEquals(64 to 48, d.videoSize(), "the source's geometry carries")
            val pts = generateSequence { d.nextFrame(convert = false)?.ptsNanos }.toList()
            assertEquals(10, pts.size, "ten frames in, ten frames out")
            // A tenth of a second apart, and the last one a frame short of
            // the second: the timing that reaches the file is each frame's
            // own timestamp, not the rate-control hint.
            assertEquals(0L, pts.first())
            assertTrue(
                abs(pts.last() - 900_000_000L) < 20_000_000L,
                "the last frame must land where it did in the source, got ${pts.last() / 1_000_000}ms",
            )
        }
    }

    @Test
    fun `the picture survives the round trip`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("libx264")
        // A solid colour, so "did the pixels survive" is a question about
        // one pixel rather than about an encoder's rate control.
        val input = Fixtures.generate(
            dir.resolve("green.mp4"),
            "-f", "lavfi", "-i", "color=c=green:size=64x48:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val before = VideoDecoder.open(input).use { d ->
            val f = assertNotNull(d.nextFrame(), "the source decoded nothing")
            val mid = ((f.height / 2) * f.width + f.width / 2) * 4
            Triple(f.rgba[mid].toInt() and 0xFF, f.rgba[mid + 1].toInt() and 0xFF, f.rgba[mid + 2].toInt() and 0xFF)
        }
        val out = dir.resolve("green-out.mp4")
        Transcoder.open(input, out, TranscodeConfig(videoCodec = "libx264", videoOptions = mapOf("crf" to "18")))
            .use { it.run() }

        VideoDecoder.open(out).use { d ->
            val f = assertNotNull(d.nextFrame(), "the output decoded nothing")
            val mid = ((f.height / 2) * f.width + f.width / 2) * 4
            val got = Triple(
                f.rgba[mid].toInt() and 0xFF,
                f.rgba[mid + 1].toInt() and 0xFF,
                f.rgba[mid + 2].toInt() and 0xFF,
            )
            // A chroma generation each way, so a band rather than equality.
            val err = maxOf(
                abs(got.first - before.first),
                maxOf(abs(got.second - before.second), abs(got.third - before.third)),
            )
            assertTrue(err <= 12, "the colour came back as $got against $before, off by $err")
        }
    }

    @Test
    fun `sound crosses with the picture`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("libx264")
        Fixtures.assumeLibraryEncoder("aac")
        val input = Fixtures.generate(
            dir.resolve("sound.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "2",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-c:a", "flac",
        )
        val out = dir.resolve("sound-out.mp4")
        Transcoder.open(
            input,
            out,
            TranscodeConfig(videoCodec = "libx264", audioCodec = "aac"),
        ).use { it.run() }

        VideoDecoder.open(out).use { d ->
            val frames = generateSequence { d.nextFrame(convert = false) }.count()
            assertEquals(20, frames, "two seconds at ten frames a second")
        }
        val audio = assertNotNull(AudioDecoder.openOrNull(out), "the output carries no audio")
        audio.use { a ->
            var bytes = 0L
            while (true) {
                val c = a.nextChunk() ?: break
                bytes += c.byteCount
            }
            // Two seconds of 44.1 kHz stereo S16, within an encoder frame of
            // padding either way -- aac pads its last frame.
            val whole = 44_100L * 2 * 4
            assertTrue(
                bytes in (whole - whole / 10)..(whole + whole / 10),
                "expected about ${whole} bytes of sound, got $bytes",
            )
        }
    }

    /**
     * The writer times audio by a running sample count from the first sample
     * it is handed, so a track whose stream starts later than the picture
     * would be pulled forward by exactly that much and the output would play
     * out of sync with nothing reporting it. The gap is padded with silence.
     */
    @Test
    fun `sound that starts late stays where it was`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("libx264")
        Fixtures.assumeLibraryEncoder("flac")
        // -itsoffset shifts the second input's timestamps, so the audio
        // STREAM starts half a second in -- not a stream that begins with
        // silence, which would prove nothing.
        val input = Fixtures.generate(
            dir.resolve("late.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "2",
            "-itsoffset", "0.5", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1.5",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-c:a", "flac",
        )
        val out = dir.resolve("late-out.mkv")
        Transcoder.open(input, out, TranscodeConfig(videoCodec = "libx264", audioCodec = "flac")).use { it.run() }

        // Where the sound actually begins in the output, in sample frames.
        val audio = assertNotNull(AudioDecoder.openOrNull(out), "the output carries no audio")
        var framesSeen = 0L
        var firstLoud = -1L
        audio.use { a ->
            while (firstLoud < 0) {
                val c = a.nextChunk() ?: break
                var i = 0
                while (i + 1 < c.byteCount) {
                    val sample = ((c.pcm[i + 1].toInt() shl 8) or (c.pcm[i].toInt() and 0xFF)).toShort().toInt()
                    if (abs(sample) > 2_000) {
                        firstLoud = framesSeen + i / 4
                        break
                    }
                    i += 4
                }
                framesSeen += c.byteCount / 4
            }
        }
        assertTrue(firstLoud >= 0, "the output has no sound in it at all")
        // Half a second at 44.1 kHz, within a tenth of a second either way:
        // flac is lossless, so the only slack is the encoder's framing.
        val expected = 44_100L / 2
        assertTrue(
            abs(firstLoud - expected) < 4_410,
            "the sound starts at frame $firstLoud, expected about $expected",
        )
    }

    /**
     * The writer has no orientation tag to pass on, so a source that stores
     * its pixels sideways has to be turned here or the output plays
     * sideways. 90 and 270 swap the geometry with it.
     */
    @Test
    fun `a rotated source comes out upright`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("libx264")
        // Two solid halves, red over green. Asymmetric on the axis the turn
        // moves, because a quarter turn keeps the byte count exactly -- the
        // writer accepts unturned pixels without a word and the output still
        // measures 96x128, so geometry alone cannot tell a turn from a
        // scramble.
        val plain = Fixtures.generate(
            dir.resolve("upright.mp4"),
            "-f", "lavfi", "-i", "color=c=red:size=128x48:rate=10", "-t", "1",
            "-f", "lavfi", "-i", "color=c=green:size=128x48:rate=10", "-t", "1",
            "-filter_complex", "[0:v][1:v]vstack=inputs=2[v]", "-map", "[v]",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        // -display_rotation is counterclockwise before display, so 90 CCW is
        // the 270 CW the decoder reports (the rotation suite's own note).
        val tagged = Fixtures.generate(
            dir.resolve("sideways.mp4"),
            "-display_rotation", "90", "-i", plain.toString(), "-c", "copy",
        )
        assertEquals(270, VideoDecoder.open(tagged).use { it.rotationDegrees() }, "the fixture must carry a rotation")

        val out = dir.resolve("upright-out.mp4")
        Transcoder.open(tagged, out, TranscodeConfig(videoCodec = "libx264")).use { it.run() }

        VideoDecoder.open(out).use { d ->
            assertEquals(96 to 128, d.videoSize(), "a quarter turn swaps the sides")
            assertEquals(0, d.rotationDegrees(), "and the output needs no further turn")
            val f = assertNotNull(d.nextFrame(), "the output decoded nothing")
            // 270 clockwise takes the top edge to the left, so red is the
            // left half and green the right.
            fun at(x: Int, y: Int): Triple<Int, Int, Int> {
                val i = (y * f.width + x) * 4
                return Triple(
                    f.rgba[i].toInt() and 0xFF,
                    f.rgba[i + 1].toInt() and 0xFF,
                    f.rgba[i + 2].toInt() and 0xFF,
                )
            }
            val left = at(f.width / 4, f.height / 2)
            val right = at(f.width * 3 / 4, f.height / 2)
            assertTrue(
                left.first > 150 && left.second < 90,
                "the source's top edge must land on the left, got $left",
            )
            assertTrue(
                right.second > 100 && right.first < 90,
                "and its bottom edge on the right, got $right",
            )
        }
    }

    @Test
    fun `a cancelled run leaves a file that plays`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("libx264")
        val input = source("long.mp4", seconds = "5")
        val out = dir.resolve("long-out.mp4")
        Transcoder.open(input, out, TranscodeConfig(videoCodec = "libx264")).use { t ->
            // Cancelled before the run rather than from a thread racing it.
            // Watching framesWritten and cancelling at ten is a bet on the
            // machine being slower than the watcher, and a quick runner wins
            // it: fifty frames of 64x48 finish before the flag is ever read.
            // Nothing about the path under test needs the race -- the loop
            // reads the flag at the top of every frame either way.
            t.cancel()
            t.run()
        }
        VideoDecoder.open(out).use { d ->
            val frames = generateSequence { d.nextFrame(convert = false) }.count()
            // Stopped early, and what it wrote is a file that opens and
            // decodes -- the trailer went in, which is the whole promise.
            assertTrue(frames in 1..49, "a cancelled run must keep what it wrote, got $frames of 50")
        }
    }

    @Test
    fun `the geometry, the cadence and the turn are decided from the source`() {
        // The three pure decisions, held here rather than through a file:
        // each one needs a source shaped a particular way, and two of them
        // no encoder in any bundle would exercise.
        assertEquals(128 to 96, Transcoder.displayedSize(128, 96, 0))
        assertEquals(128 to 96, Transcoder.displayedSize(128, 96, 180))
        assertEquals(96 to 128, Transcoder.displayedSize(128, 96, 90))
        assertEquals(96 to 128, Transcoder.displayedSize(128, 96, 270))

        // Ten frames a second, and a source that gave only one frame.
        assertEquals(10, Transcoder.measuredFps(0, 100_000_000L))
        assertEquals(30, Transcoder.measuredFps(0, null))
        assertEquals(Transcoder.FALLBACK_FPS, Transcoder.measuredFps(500, 500), "no cadence between two equal stamps")

        // A 2x1 image, red then green, turned each way. Written out as
        // pixels rather than described, because a rotation that transposes
        // instead of turning passes every description.
        val src = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
        )
        // 90 clockwise: the row becomes a column, red on top.
        assertContentEquals(
            byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(),
                0, 255.toByte(), 0, 255.toByte(),
            ),
            Transcoder.rotate(src, 2, 1, 90, ByteArray(8)),
        )
        // 180: the row reverses.
        assertContentEquals(
            byteArrayOf(
                0, 255.toByte(), 0, 255.toByte(),
                255.toByte(), 0, 0, 255.toByte(),
            ),
            Transcoder.rotate(src, 2, 1, 180, ByteArray(8)),
        )
        // 270: the column again, green on top.
        assertContentEquals(
            byteArrayOf(
                0, 255.toByte(), 0, 255.toByte(),
                255.toByte(), 0, 0, 255.toByte(),
            ),
            Transcoder.rotate(src, 2, 1, 270, ByteArray(8)),
        )
    }
    /**
     * A cancelled transcode is finished, not paused -- and resuming it used to
     * destroy what it had produced.
     *
     * The second `run()` opened a fresh writer on the same path, and avio_open
     * truncates, so the valid short file from the first run was replaced. The
     * first writer was orphaned by the field assignment at the same moment,
     * taking its confined arena, its format and codec contexts and its file
     * handle with it, because close() only ever sees the last one. Measured
     * before the guard: 153 frames and 1.6 MB of output, then a second run()
     * carrying the frame counter on from 153 over a file it had just emptied.
     *
     * Cancelling and retrying is a natural thing for a caller to try, so the
     * refusal names what to do instead.
     */
    @Test
    fun `run is one-shot, and says so after a cancel`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val src = Fixtures.generate(
            dir.resolve("one-shot-src.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=640x480:rate=25", "-t", "30",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
        )
        val out = dir.resolve("one-shot.mp4")
        Transcoder.open(
            src,
            out,
            TranscodeConfig(videoCodec = "libx264", videoOptions = mapOf("preset" to "ultrafast")),
        ).use { t ->
            // run() belongs to the thread that opened it; cancel() is the one
            // call the class documents as safe from anywhere.
            val canceller = thread { Thread.sleep(400); t.cancel() }
            t.run()
            canceller.join(5_000)

            val afterCancel = t.framesWritten
            val sizeAfterCancel = Files.size(out)
            assertTrue(afterCancel > 0, "the cancelled run must have written something")
            assertTrue(sizeAfterCancel > 0, "and left a file")

            val message = assertFailsWith<IllegalStateException> { t.run() }.message
            assertEquals(
                "run() is one-shot; a cancelled transcode is finished, not paused",
                message,
            )
            assertEquals(afterCancel, t.framesWritten, "a refused run must not advance the counter")
            assertEquals(sizeAfterCancel, Files.size(out), "and must not touch the file it already wrote")
        }
    }

    /**
     * The guard against a mid-stream geometry change stands beside the first
     * two frames, and the second of them was handed the FIRST frame's
     * dimensions -- so for that one frame it compared first against first and
     * could not fail. A second frame of another size then went to a writer
     * opened at the first's geometry, and what surfaced was the writer's own
     * `require` -- an IllegalArgumentException -- where everything else in this
     * class fails closed with a LibavException. With rotation applied it is
     * worse than a wrong exception type: rotate() would read off the end of a
     * buffer sized for the other geometry.
     *
     * MPEG-TS carries a resolution change by design, and a one-frame first
     * half puts the change exactly where the guard was blind.
     */
    @Test
    fun `a source that changes size at its second frame is refused by name`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeFormats()
        Fixtures.assumeLibraryEncoder("libx264")
        val one = Fixtures.generate(
            dir.resolve("one-frame.ts"),
            "-f", "lavfi", "-i", "color=c=red:size=64x64:rate=10", "-frames:v", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-f", "mpegts",
        )
        val rest = Fixtures.generate(
            dir.resolve("rest.ts"),
            "-f", "lavfi", "-i", "color=c=lime:size=128x96:rate=10", "-t", "0.5",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-f", "mpegts",
        )
        val mixed = dir.resolve("switch-at-two.ts")
        Files.newOutputStream(mixed).use { out ->
            Files.newInputStream(one).use { it.copyTo(out) }
            Files.newInputStream(rest).use { it.copyTo(out) }
        }

        val thrown = assertFailsWith<LibavException> {
            Transcoder.open(mixed, dir.resolve("switched.mp4"), TranscodeConfig(videoCodec = "libx264"))
                .use { it.run() }
        }
        assertTrue(
            thrown.message?.contains("changed geometry") == true,
            "the transcoder's own refusal must be what surfaces, got: ${thrown.message}",
        )
    }
}
