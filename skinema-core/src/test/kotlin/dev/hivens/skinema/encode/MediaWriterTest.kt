package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Encode acceptance: push synthetic RGBA frames through [MediaWriter] and
 * decode the result back with [VideoDecoder] -- a semantic round-trip
 * (frame count, geometry, a solid colour within yuv tolerance), the
 * project's "assert on meaning, not checksums" style. Gated on the loaded
 * libav actually carrying the encoder, so a decode-only bundle skips.
 */
class MediaWriterTest {

    private val dir: Path = Files.createTempDirectory("skinema-encode-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun solidGreen(w: Int, h: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        for (p in 0 until w * h) {
            rgba[p * 4] = 0
            rgba[p * 4 + 1] = 255.toByte()
            rgba[p * 4 + 2] = 0
            rgba[p * 4 + 3] = 255.toByte()
        }
        return rgba
    }

    @Test
    fun `encodes RGBA frames to a file that decodes back`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val w = 64
        val h = 64
        val fps = 10
        val frames = 10
        val out = dir.resolve("out.mp4")

        MediaWriter.open(
            out,
            VideoEncodeConfig("libx264", w, h, fps, options = mapOf("preset" to "ultrafast", "crf" to "23")),
        ).use { writer ->
            repeat(frames) { i -> writer.writeFrame(solidGreen(w, h), i * 1_000_000_000L / fps) }
            writer.finish()
        }

        assertTrue(Files.size(out) > 0, "the muxed file must not be empty")
        VideoDecoder.open(out).use { decoder ->
            val decoded = generateSequence { decoder.nextFrame() }.toList()
            // ultrafast x264 may merge the static tail; tolerate +-1 frame.
            assertTrue(decoded.size in (frames - 1)..(frames + 1), "expected ~$frames frames, got ${decoded.size}")
            val first = decoded.first()
            assertEquals(w, first.width)
            assertEquals(h, first.height)
            val i = (h / 2 * w + w / 2) * 4
            val r = first.rgba[i].toInt() and 0xFF
            val g = first.rgba[i + 1].toInt() and 0xFF
            val b = first.rgba[i + 2].toInt() and 0xFF
            assertTrue(g > 180 && r < 80 && b < 80, "solid green must round-trip, got r=$r g=$g b=$b")
        }
    }

    @Test
    fun `an unknown encoder fails closed`() {
        Fixtures.assumeDecodeEnvironment()
        val out = dir.resolve("nope.mp4")
        val threw = runCatching {
            MediaWriter.open(out, VideoEncodeConfig("definitely-not-a-codec", 64, 64, 10))
        }.isFailure
        assertTrue(threw, "an unknown encoder name must throw, not half-open")
    }
}
