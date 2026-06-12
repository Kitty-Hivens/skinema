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

class VideoDecoderTest {

    private val dir: Path = Files.createTempDirectory("skinema-decoder-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
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
    private fun assertDecodesEveryFrame(name: String, expectedFrames: Int, vararg encodeArgs: String) {
        val video = Fixtures.generate(dir.resolve(name), *encodeArgs)
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
        Fixtures.assumeEncoder("libaom-av1")
        assertDecodesEveryFrame(
            "av1.mp4", 10,
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libaom-av1", "-cpu-used", "8", "-crf", "40",
        )
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
        // The fallback for builds without libwebpdemux: ffmpeg's webp
        // decoder handles stills (animations are libwebp's job -- see
        // WebpAnimSourceTest).
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
    fun `garbage input fails closed with LibavException`() {
        Fixtures.assumeDecodeEnvironment()
        val junk = dir.resolve("junk.mp4")
        Files.write(junk, ByteArray(4096) { (it * 31).toByte() })
        assertFailsWith<LibavException> { VideoDecoder.open(junk) }
    }
}
