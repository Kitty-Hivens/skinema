package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.VideoDecoder
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hardware-encode acceptance (M13). Like the hw-decode test, a headless CI
 * runner has no GPU, so the real round-trip is opt-in (SKINEMA_TEST_HWENC=1)
 * and runs on a dev box with a working VAAPI device -- the manual acceptance
 * the ROADMAP's M13 entry calls for. It pushes synthetic RGBA frames through
 * MediaWriter's GPU path (RGBA -> NV12 -> a VAAPI surface -> h264_vaapi) and
 * decodes the result back, the same semantic round-trip as the software
 * [MediaWriterTest]. The fail-closed assertion needs no GPU, so it runs
 * wherever the encoder is present.
 */
class MediaWriterHwTest {

    private val dir: Path = Files.createTempDirectory("skinema-hwencode-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun assumeHwEncAcceptance() {
        assumeTrue(
            System.getenv("SKINEMA_TEST_HWENC") == "1",
            "hardware-encode acceptance is opt-in (SKINEMA_TEST_HWENC=1) -- a GPU-less CI cannot run it",
        )
        Fixtures.assumeLibraryEncoder("h264_vaapi")
    }

    private fun solidGreen(w: Int, h: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        for (p in 0 until w * h) {
            rgba[p * 4 + 1] = 255.toByte() // G
            rgba[p * 4 + 3] = 255.toByte() // A
        }
        return rgba
    }

    @Test
    fun `VAAPI encodes RGBA frames to a file that decodes back`() {
        assumeHwEncAcceptance()
        val w = 128
        val h = 128
        val fps = 10
        val frames = 10
        val out = dir.resolve("vaapi.mp4")

        MediaWriter.open(out, VideoEncodeConfig("h264_vaapi", w, h, fps)).use { writer ->
            repeat(frames) { i -> writer.writeFrame(solidGreen(w, h), i * 1_000_000_000L / fps) }
            writer.finish()
        }

        assertTrue(Files.size(out) > 0, "the muxed file must not be empty")
        VideoDecoder.open(out).use { decoder ->
            val decoded = generateSequence { decoder.nextFrame() }.toList()
            assertTrue(decoded.size in (frames - 1)..(frames + 1), "expected ~$frames frames, got ${decoded.size}")
            val first = decoded.first()
            assertEquals(w, first.width)
            assertEquals(h, first.height)
            val i = (h / 2 * w + w / 2) * 4
            val r = first.rgba[i].toInt() and 0xFF
            val g = first.rgba[i + 1].toInt() and 0xFF
            val b = first.rgba[i + 2].toInt() and 0xFF
            assertTrue(g > 150 && r < 100 && b < 100, "solid green must round-trip through VAAPI, got r=$r g=$g b=$b")
        }
    }

    @Test
    fun `a hardware encoder with an unusable device fails closed`() {
        Fixtures.assumeLibraryEncoder("h264_vaapi")
        val out = dir.resolve("baddev.mp4")
        val threw = runCatching {
            MediaWriter.open(out, VideoEncodeConfig("h264_vaapi", 128, 128, 10, device = "/dev/dri/renderD999"))
        }.isFailure
        assertTrue(threw, "an unopenable VAAPI device must throw, not half-open")
        assertTrue(Files.notExists(out), "a failed open must leave no output file")
    }
}
