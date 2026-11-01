package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Family names: one word both platforms take, the encoder left to the platform. */
class MediaWriterFamilyTest {

    private val dir = Files.createTempDirectory("skinema-family")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a family name stands for its encoders hardware first and an encoder name for itself`() {
        assertEquals(listOf("h264_vaapi", "libx264"), MediaWriter.encoderCandidates("h264"))
        assertEquals(listOf("hevc_vaapi", "libx265"), MediaWriter.encoderCandidates("hevc"))
        assertEquals(listOf("libopus"), MediaWriter.encoderCandidates("opus"))
        assertEquals(listOf("libx264"), MediaWriter.encoderCandidates("libx264"))
        assertEquals(listOf("aac"), MediaWriter.encoderCandidates("aac"))
    }

    /**
     * A family that cannot open is refused under the name the caller used,
     * even when it stands for a single encoder whose own name does not
     * contain it. A .wav takes no picture, so this is refused whether or not
     * the FFmpeg in use carries libvpx.
     */
    @Test
    fun `a refused family is refused by the family's name`() {
        val e = assertFailsWith<LibavException> { MediaWriter.open(dir.resolve("x.wav"), VideoEncodeConfig("vp8", 64, 64, 10)).close() }
        assertTrue("vp8" in e.message.orEmpty(), "the refusal names the family: ${e.message}")
    }

    /**
     * Whichever H.264 encoder this machine has: VAAPI where a GPU answers,
     * libx264 where it does not. Gated on libx264 so the family is sure to
     * resolve to something, which is what makes a broken mapping fail here
     * rather than skip.
     */
    @Test
    fun `the h264 family writes a file that decodes back`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val out = dir.resolve("family.mp4")
        val green = ByteArray(64 * 64 * 4) { i -> if (i % 4 == 1 || i % 4 == 3) 0xFF.toByte() else 0 }
        MediaWriter.open(out, VideoEncodeConfig("h264", 64, 64, 10)).use { writer ->
            repeat(5) { i -> writer.writeFrame(green, i * 100_000_000L) }
            writer.finish()
        }
        VideoDecoder.open(out).use { d ->
            var count = 0
            var last: VideoDecoder.RgbaFrame? = null
            while (true) {
                last = d.nextFrame() ?: break
                count++
            }
            assertEquals(5, count)
            val f = last!!
            val i = ((f.height / 2) * f.width + f.width / 2) * 4
            val g = f.rgba[i + 1].toInt() and 0xFF
            assertTrue(g > 180, "green survives the round trip, got $g")
        }
    }
}
