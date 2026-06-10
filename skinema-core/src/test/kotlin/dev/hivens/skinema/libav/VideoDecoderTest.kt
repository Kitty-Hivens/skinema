package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `vp9 with alpha preserves transparency`() {
        Fixtures.assumeDecodeEnvironment()
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

    @Test
    fun `garbage input fails closed with LibavException`() {
        Fixtures.assumeDecodeEnvironment()
        val junk = dir.resolve("junk.mp4")
        Files.write(junk, ByteArray(4096) { (it * 31).toByte() })
        assertFailsWith<LibavException> { VideoDecoder.open(junk) }
    }
}
